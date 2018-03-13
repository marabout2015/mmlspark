// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark.io.powerbi

import com.microsoft.ml.spark.io.http.{CustomOutputParser, HTTPResponseData, SimpleHTTPTransformer}
import org.apache.log4j.{LogManager, Logger}
import org.apache.spark.sql.functions.{col, struct}
import org.apache.spark.sql.streaming.DataStreamWriter
import org.apache.spark.sql.{DataFrame, ForeachWriter, Row}

private class StreamMaterializer extends ForeachWriter[Row] {

  override def open(partitionId: Long, version: Long): Boolean = true

  override def process(value: Row): Unit = {}

  override def close(errorOrNull: Throwable): Unit = {}

}

object PowerBIWriter {

  val logger: Logger = LogManager.getRootLogger

  private def prepareDF(df: DataFrame, url: String): DataFrame = {

    new SimpleHTTPTransformer()
      .setUrl(url)
      .setMaxBatchSize(Integer.MAX_VALUE)
      .setFlattenOutputBatches(false)
      .setOutputParser(new CustomOutputParser().setUDF({x: HTTPResponseData => x}))
      .setInputCol("input")
      .setOutputCol("output")
      .transform(df.select(struct(df.columns.map(col): _*).alias("input")))
  }

  def stream(df: DataFrame, url: String): DataStreamWriter[Row] = {
      prepareDF(df, url).writeStream.foreach(new StreamMaterializer)
  }

  def write(df: DataFrame, url: String): Unit = {
    prepareDF(df, url).foreachPartition(it => it.foreach(_ => ()))
  }

}
