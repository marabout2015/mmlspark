// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.awt.Color
import java.awt.color.ColorSpace
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

import com.microsoft.ml.spark.BinaryFileReader.recursePath
import com.microsoft.ml.spark.schema.ImageSchema
import org.apache.commons.io.IOUtils
import org.apache.hadoop.conf.{Configuration => HConf}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.{IOUtils => HUtils}
import org.apache.spark.SparkContext
import org.apache.spark.image.ImageFileFormat
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.opencv.core.{CvException, Mat, MatOfByte}
import org.opencv.imgcodecs.Imgcodecs
import org.apache.spark.image.ConfUtils

object ImageReader {

  /** This object will load the openCV binaries when the object is referenced
    * for the first time, subsequent references will not re-load the binaries.
    * In spark, this loads one copy for each running jvm, instead of once per partition.
    * This technique is similar to that used by the cntk_jni jar,
    * but in the case where microsoft cannot edit the jar
    */
  object OpenCVLoader {
    import org.opencv.core.Core
    new NativeLoader("/nu/pattern/opencv").loadLibraryByName(Core.NATIVE_LIBRARY_NAME)
  }

  private[spark] def loadOpenCVFunc[A](it: Iterator[A]) = {
    OpenCVLoader
    it
  }

  private[spark] def loadOpenCV(df: DataFrame):DataFrame ={
    val encoder = RowEncoder(df.schema)
    df.mapPartitions(loadOpenCVFunc)(encoder)
  }

  /**
    * (Scala-specific) OpenCV type mapping supported
    */
  val ocvTypes: Map[String, Int] = Map(
    "CV_8U" -> 0, "CV_8UC1" -> 0, "CV_8UC3" -> 16, "CV_8UC4" -> 24
  )

  /**
    * Convert the compressed image (jpeg, png, etc.) into OpenCV
    * representation and store it in DataFrame Row
    *
    * @param origin Arbitrary string that identifies the image
    * @param bytes Image bytes (for example, jpeg)
    * @return DataFrame Row or None (if the decompression fails)
    */
  def decodeWithoutOpenCV(origin: String, bytes: Array[Byte]): Option[Row] = {

    val img = ImageIO.read(new ByteArrayInputStream(bytes))

    if (img == null) {
      None
    } else {
      val isGray = img.getColorModel.getColorSpace.getType == ColorSpace.TYPE_GRAY
      val hasAlpha = img.getColorModel.hasAlpha

      val height = img.getHeight
      val width = img.getWidth
      val (nChannels, mode) = if (isGray) {
        (1, ocvTypes("CV_8UC1"))
      } else if (hasAlpha) {
        (4, ocvTypes("CV_8UC4"))
      } else {
        (3, ocvTypes("CV_8UC3"))
      }

      val imageSize = height * width * nChannels
      assert(imageSize < 1e9, "image is too large")
      val decoded = Array.ofDim[Byte](imageSize)

      // Grayscale images in Java require special handling to get the correct intensity
      if (isGray) {
        var offset = 0
        val raster = img.getRaster
        for (h <- 0 until height) {
          for (w <- 0 until width) {
            decoded(offset) = raster.getSample(w, h, 0).toByte
            offset += 1
          }
        }
      } else {
        var offset = 0
        for (h <- 0 until height) {
          for (w <- 0 until width) {
            val color = new Color(img.getRGB(w, h))

            decoded(offset) = color.getBlue.toByte
            decoded(offset + 1) = color.getGreen.toByte
            decoded(offset + 2) = color.getRed.toByte
            if (nChannels == 4) {
              decoded(offset + 3) = color.getAlpha.toByte
            }
            offset += nChannels
          }
        }
      }

      // the internal "Row" is needed, because the image is a single DataFrame column
      Some(Row(Row(origin, height, width, nChannels, mode, decoded)))
    }
  }

  /** Convert the image from compressd (jpeg, etc.) into OpenCV representation and store it in Row
    * See ImageSchema for details.
    *
    * @param filename arbitrary string
    * @param bytes image bytes (for example, jpeg)
    * @return returns None if decompression fails
    */
  def decode(filename: String, bytes: Array[Byte]): Option[Row] = {
    val mat = new MatOfByte(bytes: _*)
    val decodedOpt = try {
      Some(Imgcodecs.imdecode(mat, Imgcodecs.CV_LOAD_IMAGE_COLOR))
    } catch {
      case _:CvException => None
    }

    decodedOpt match {
      case Some(decoded) if !decoded.empty() =>
        val ocvBytes = new Array[Byte](decoded.total.toInt * decoded.elemSize.toInt)
        // extract OpenCV bytes
        decoded.get(0, 0, ocvBytes)
        // type: CvType.CV_8U
        Some(Row(filename, decoded.height, decoded.width, decoded.`type`, ocvBytes))
      case _ => None
    }
  }

  def encode(row: InternalRow, extension: String): Array[Byte] = {
    val mat = new Mat(row.getInt(1),row.getInt(2),row.getInt(3))
    mat.put(0, 0, row.getBinary(4))
    val out = new MatOfByte()
    Imgcodecs.imencode(extension, mat, out)
    out.toArray
  }

  def encode(row: Row, extension: String): Array[Byte] = {
    val mat = new Mat(row.getInt(1),row.getInt(2),row.getInt(3))
    mat.put(0, 0, row.getAs[Array[Byte]](4))
    val out = new MatOfByte()
    Imgcodecs.imencode(extension, mat, out)
    out.toArray
  }

  /** Read the directory of images from the local or remote source
    *
    * @param path      Path to the image directory
    * @param recursive Recursive search flag
    * @return          DataFrame with a single column of "images", see "columnSchema" for details
    */
  def read(path: String, recursive: Boolean, spark: SparkSession,
           sampleRatio: Double = 1, inspectZip: Boolean = true, seed: Long = 0L): DataFrame = {
    val p = new Path(path)
    val globs = if (recursive){
      recursePath(p.getFileSystem(spark.sparkContext.hadoopConfiguration), p, {fs => fs.isDirectory})
        .map(g => g) ++ Array(p)
    }else{
      Array(p)
    }
    spark.read.format(classOf[ImageFileFormat].getName)
      .option("subsample", sampleRatio)
      .option("seed", seed)
      .option("inspectZip", inspectZip).load(globs.map(_.toString):_*)
  }

  /** Read the directory of image files from the local or remote source
    *
    * @param path       Path to the directory
    * @return           DataFrame with a single column of "imageFiles", see "columnSchema" for details
    */
  def stream(path: String, spark: SparkSession,
             sampleRatio: Double = 1, inspectZip: Boolean = true, seed: Long = 0L): DataFrame = {
    val p = new Path(path)
    spark.readStream.format(classOf[ImageFileFormat].getName)
      .option("subsample", sampleRatio)
      .option("seed", seed)
      .option("inspectZip",inspectZip).schema(ImageSchema.schema).load(p.toString)
  }

  def readFromPaths(df: DataFrame, pathCol:String, imageCol:String = "image"): DataFrame ={
    val outputSchema = df.schema.add(imageCol, ImageSchema.columnSchema)
    val encoder = RowEncoder(outputSchema)
    val hconf = ConfUtils.getHConf(df)
    df.mapPartitions { rows =>
      ImageReader.OpenCVLoader
      rows.map { row =>
        val path = new Path(row.getAs[String](pathCol))
        val fs = path.getFileSystem(hconf.value)
        val is = fs.open(path)
        val imageRow = ImageReader.decode(path.toString,IOUtils.toByteArray(is)).getOrElse(Row(None))
        val ret = Row.merge(Seq(row, Row(imageRow)):_*)
        ret
      }
    }(encoder)
  }

  def write(df: DataFrame,
            basePath: String,
            pathCol:String = "filenames",
            imageCol:String = "image",
            encoding: String=".png"):Unit ={

    val hconf = ConfUtils.getHConf(df)
    df.select(imageCol, pathCol).foreachPartition { rows =>
      val fs = FileSystem.get(new Path(basePath).toUri, hconf.value)
      ImageReader.OpenCVLoader
      rows.foreach {row =>
        val rowInternals = row.getStruct(0)
        val bytes = ImageReader.encode(rowInternals, encoding)
        val outputPath = new Path(basePath,row.getString(1))
        val os = fs.create(outputPath)
        val is = new ByteArrayInputStream(bytes)
        try
          os.write(IOUtils.toByteArray(is))
        finally {
          os.close()
          is.close()
        }
      }
    }
  }

}
