package com.github.jelmerk.spark.knn.evaluation

import scala.util.Try
import org.apache.spark.ml.evaluation.Evaluator
import org.apache.spark.ml.param.{Param, ParamMap}
import org.apache.spark.ml.util.{DefaultParamsReadable, DefaultParamsWritable, Identifiable}
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{ArrayType, StringType, StructType}

/**
  * Companion class for KnnEvaluator.
  */
object KnnEvaluator extends DefaultParamsReadable[KnnEvaluator] {
  override def load(path: String): KnnEvaluator = super.load(path)
}

/**
  * Evaluator for knn algorithms, which expects two input columns, the exact neighbors and approximate neighbors. It compares
  * the results to determine the accuracy of the approximate results. Typically you will want to compute this over a
  * small sample given the cost of computing the exact results on a large index.
  *
  * @param uid identifier
  */
class KnnEvaluator(override val uid: String) extends Evaluator with DefaultParamsWritable {

  def this() = this(Identifiable.randomUID("knn_eval"))

  /**
    * Param for the column name for the approximate results.
    * Default: "approximateNeighbors"
    *
    * @group param
    */
  val approximateNeighborsCol = new Param[String](this, "approximateNeighborsCol", "column containing the approximate neighbors")

  /**
    * @group getParam
    */
  def geApproximateNeighborsCol: String = $(approximateNeighborsCol)

  /**
    * @group setParam
    */
  def setApproximateNeighborsCol(value: String): this.type = set(approximateNeighborsCol, value)

  /**
    * Param for the column name for the exact results.
    * Default: "exactNeighbors"
    *
    * @group param
    */
  val exactNeighborsCol = new Param[String](this, "exactNeighborsCol", "column containing the exact neighbors")

  /**
    * @group getParam
    */
  def getExactNeighborsCol: String = $(exactNeighborsCol)

  /**
    * @group setParam
    */
  def setExactNeighborsCol(value: String): this.type = set(exactNeighborsCol, value)

  /**
    * Returns the accuracy of the approximate results.
    *
    * @param dataset a dataset
    * @return the accuracy of the approximate results
    */
  override def evaluate(dataset: Dataset[_]): Double = {
    import dataset.sparkSession.implicits._

    requireColumnOfTypeNeighbor(dataset.schema, getExactNeighborsCol)
    requireColumnOfTypeNeighbor(dataset.schema, geApproximateNeighborsCol)

    dataset
      .select(
        col(s"$getExactNeighborsCol.neighbor").cast(ArrayType(StringType)),
        col(s"$geApproximateNeighborsCol.neighbor").cast(ArrayType(StringType))
      )
      .as[(Seq[String], Seq[String])]
      .mapPartitions( it => it.map { case (exactNeighbors, approximateNeighbors) =>
         exactNeighbors.toSet.intersect(approximateNeighbors.toSet).size -> exactNeighbors.size
      })
      .toDF("numMatching", "numResults")
      .select(when(sum($"numResults") === 0, 1.0).otherwise(sum($"numMatching") / sum($"numResults")))
      .as[Double]
      .collect()
      .head
  }

  override def copy(extra: ParamMap): Evaluator = this.defaultCopy(extra)

  override def isLargerBetter: Boolean = true

  private def requireColumnOfTypeNeighbor(schema: StructType, column: String): Unit =
    require(isNeighborColumn(schema, column), s"Column $column is not of type array of neighbors.")

  private def isNeighborColumn(schema: StructType, column: String): Boolean = {
    Try(schema(getExactNeighborsCol).dataType).toOption
      .collect { case t: ArrayType => t.elementType }
      .collect { case t: StructType => t.fields.map(_.name).toList.sorted }
      .collect { case List("distance", "neighbor") => true }
      .getOrElse(false)
  }

  setDefault(approximateNeighborsCol -> "approximateNeighbors", exactNeighborsCol -> "exactNeighbors")
}
