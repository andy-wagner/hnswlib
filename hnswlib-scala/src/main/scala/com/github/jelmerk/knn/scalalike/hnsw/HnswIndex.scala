package com.github.jelmerk.knn.scalalike.hnsw

import java.io.{File, InputStream}
import java.nio.file.Path

import com.github.jelmerk.knn.hnsw.{HnswIndex => JHnswIndex}
import com.github.jelmerk.knn.DistanceFunction
import com.github.jelmerk.knn.scalalike.{ScalaIndexAdapter, Index, Item}

object HnswIndex {

  /**
    * Restores a [[HnswIndex]] from an InputStream.
    *
    * @param inputStream InputStream to restore the index from
    *
    * @tparam TId Type of the external identifier of an item
    * @tparam TVector Type of the vector to perform distance calculation on
    * @tparam TItem Type of items stored in the index
    * @tparam TDistance Type of distance between items (expect any numeric type: float, double, int, ..)
    * @return The restored index
    */
  def load[TId,  TVector, TItem <: Item[TId, TVector], TDistance](inputStream: InputStream)
    : HnswIndex[TId, TVector, TItem, TDistance] = new HnswIndex(JHnswIndex.load(inputStream))

  /**
    * Restores a [[HnswIndex]] from a File.
    *
    * @param file File to read from
    *
    * @tparam TId Type of the external identifier of an item
    * @tparam TVector Type of the vector to perform distance calculation on
    * @tparam TItem Type of items stored in the index
    * @tparam TDistance Type of distance between items (expect any numeric type: float, double, int, ..)
    * @return The restored index
    */
  def load[TId,  TVector, TItem <: Item[TId, TVector], TDistance](file: File)
    : HnswIndex[TId, TVector, TItem, TDistance] =
      new HnswIndex(JHnswIndex.load(file))

  /**
    * Restores a [[HnswIndex]] from a Path.
    *
    * @param path Path to read from
    *
    * @tparam TId Type of the external identifier of an item
    * @tparam TVector Type of the vector to perform distance calculation on
    * @tparam TItem Type of items stored in the index
    * @tparam TDistance Type of distance between items (expect any numeric type: float, double, int, ..)
    * @return The restored index
    */
  def load[TId,  TVector, TItem <: Item[TId, TVector], TDistance](path: Path)
    : HnswIndex[TId, TVector, TItem, TDistance] =
      new HnswIndex(JHnswIndex.load(path))

  /**
    * Construct a new [[HnswIndex]].
    *
    * @param distanceFunction the distance function
    * @param maxItemCount the maximum number of elements in the index
    * @param m              Sets the number of bi-directional links created for every new element during construction. Reasonable range
    *                       for m is 2-100. Higher m work better on datasets with high intrinsic dimensionality and/or high recall,
    *                       while low m work better for datasets with low intrinsic dimensionality and/or low recalls. The parameter
    *                       also determines the algorithm's memory consumption.
    *                       As an example for d = 4 random vectors optimal m for search is somewhere around 6, while for high dimensional
    *                       datasets (word embeddings, good face descriptors), higher M are required (e.g. m = 48, 64) for optimal
    *                       performance at high recall. The range mM = 12-48 is ok for the most of the use cases. When m is changed one
    *                       has to update the other parameters. Nonetheless, ef and efConstruction parameters can be roughly estimated by
    *                       assuming that m * efConstruction is a constant.
    * @param ef             The size of the dynamic list for the nearest neighbors (used during the search). Higher ef leads to more
    *                       accurate but slower search. The value ef of can be anything between k and the size of the dataset.
    * @param efConstruction The parameter has the same meaning as ef, but controls the index time / index precision. Bigger efConstruction
    *                       leads to longer construction, but better index quality. At some point, increasing efConstruction does not
    *                       improve the quality of the index. One way to check if the selection of ef_construction was ok is to measure
    *                       a recall for M nearest neighbor search when ef = efConstruction: if the recall is lower than 0.9, then
    *                       there is room for improvement
    * @param removeEnabled  enable or disable the experimental remove operation. Indices that support removes will consume more memory
    * @param ordering used to compare the distances returned by the distancefunction
    * @tparam TId Type of the external identifier of an item
    * @tparam TVector Type of the vector to perform distance calculation on
    * @tparam TItem Type of items stored in the index
    * @tparam TDistance Type of distance between items (expect any numeric type: float, double, int, ..)
    * @return
    */
  def apply[TId,  TVector, TItem <: Item[TId, TVector], TDistance](
    distanceFunction: (TVector, TVector) => TDistance,
    maxItemCount : Int,
    m: Int = JHnswIndex.Builder.DEFAULT_M,
    ef: Int = JHnswIndex.Builder.DEFAULT_EF,
    efConstruction: Int = JHnswIndex.Builder.DEFAULT_EF_CONSTRUCTION,
    removeEnabled: Boolean = JHnswIndex.Builder.DEFAULT_REMOVE_ENABLED)(implicit ordering: Ordering[TDistance])
      : HnswIndex[TId, TVector, TItem, TDistance] = {

    val jDistanceFunction = new DistanceFunction[TVector, TDistance] {
      override def distance(u: TVector, v: TVector): TDistance = distanceFunction(u, v)
    }

    val builder = JHnswIndex.newBuilder(jDistanceFunction, ordering, maxItemCount)
      .withM(m)
      .withEf(ef)
      .withEfConstruction(efConstruction)

    val jIndex =
      if(removeEnabled) builder.withRemoveEnabled().build[TId, TItem]()
      else builder.build[TId, TItem]()

    new HnswIndex[TId, TVector, TItem, TDistance](jIndex)
  }

  /**
    * Implementation of Index that implements the hnsw algorithm.
    *
    * @see See [[https://arxiv.org/abs/1603.09320]] for more information.
    *
    * @param delegate the java index to delegate calls to
    *
    * @tparam TId Type of the external identifier of an item
    * @tparam TVector Type of the vector to perform distance calculation on
    * @tparam TItem Type of items stored in the index
    * @tparam TDistance Type of distance between items (expect any numeric type: float, double, int, ..)
    */
  @SerialVersionUID(1L)
  class HnswIndex[TId, TVector, TItem <: Item[TId, TVector], TDistance](
    delegate: JHnswIndex[TId, TVector, TItem, TDistance])
      extends ScalaIndexAdapter[TId, TVector, TItem ,TDistance](delegate) {

    /**
      * Read only view on top of this index that uses pairwise comparision when doing distance search. And as
      * such can be used as a baseline for assessing the precision of the index.
      * Searches will be really slow but give the correct result every time.
      */
    def asExactIndex: Index[TId, TVector, TItem, TDistance] =
      new ScalaIndexAdapter(delegate.asExactIndex())

    /**
      * The number of bi-directional links created for every new element during construction.
      */
    val m: Int = delegate.getM

    /**
      * The size of the dynamic list for the nearest neighbors (used during the search)
      */
    val ef: Int = delegate.getEf

    /**
      * Returns the parameter has the same meaning as ef, but controls the index time / index precision.
      */
    val efConstruction: Int = delegate.getEfConstruction
  }

}