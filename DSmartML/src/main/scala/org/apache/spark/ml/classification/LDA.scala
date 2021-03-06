package org.apache.spark.ml.classification

import org.apache.spark.SparkContext
import org.apache.spark.ml.PredictorParams
import org.apache.spark.ml.feature.StandardScalerModel
import org.apache.spark.ml.linalg.{BLAS, DenseVector, Matrix, Vector, Vectors}
import org.apache.spark.ml.param.{BooleanParam, ParamMap}
import org.apache.spark.ml.util._
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions.{col, lit}


/**
  * Params for linear discriminant analysis Classifiers.
  * @author Ahmed Eissa
  * @version 1.0
  * @Date 22/3/2019
  */
private[classification] trait LDAParams extends PredictorParams  {


  /**
    * The Scaled Data parameter.
    * (default = 1.0).
    * @group param
    */
  final val scaledData: BooleanParam = new BooleanParam(this, "scaledData", "is the input data scaled ? or we should scale it")

  /** @group getParam */
  final def getscaled_data: Boolean = $(scaledData)
}

/**
  * Linear discriminant analysis Algorithm Implementation (Estimator)
  * @constructor create an object of LDA allow us to train a QDA Model
  * @author Ahmed Eissa
  * @version 1.0
  * @Date 22/3/2019
  */
class LDA (override val uid: String ) //, sc: SparkContext)
  extends ProbabilisticClassifier[Vector, LDA, LDAModel]
    with LDAParams with DefaultParamsWritable {

  var sc: SparkContext = null

  //def this( sc: SparkContext) = this(Identifiable.randomUID("lda") , sc )
  def this( ) = this(Identifiable.randomUID("lda")  )

  def setScaledData(value: Boolean): this.type = set(scaledData, value)
  setDefault(scaledData -> false)

  /**
    * this function train LDA Algorithm and return LDAModel
    * @param dataset Inuput Dataset
    * @return Trained LDAModel object
    */
  override protected def train(dataset: Dataset[_]): LDAModel = {

    var InvMainCovarianceMatrix:org.apache.spark.mllib.linalg.DenseMatrix = null
    var meanMap: Map[Int ,Vector] = null
    var ClassesProbMap : Map[Int , Double] = null
    var totalObservations:Double = 0.0
    var scalerModel : StandardScalerModel = null

    if($(scaledData))
    {

    }

    //Get the number of classes.
    val numClasses = getNumClasses(dataset)


    val instr = Instrumentation.create(this, dataset)
    instr.logParams(labelCol, featuresCol,  predictionCol, rawPredictionCol,
      probabilityCol, scaledData, thresholds)

    //get number of features
    val numFeatures = dataset.select(col($(featuresCol))).head().getAs[Vector](0).size
    instr.logNumFeatures(numFeatures)
    //val w = if (!isDefined(weightCol) || $(weightCol).isEmpty) lit(1.0) else col($(weightCol))

    //calculate statistics from the dataset (count and sum per class)
    val aggregated = dataset.select(col($(labelCol)), lit(1.0), col($(featuresCol))).rdd
      .map { row => (row.getDouble(0), (row.getDouble(1), row.getAs[Vector](2)))
      }.aggregateByKey[(Double, DenseVector)]((0.0, Vectors.zeros(numFeatures).toDense))(
      seqOp = {
        case ((countSum: Double, featureSum: DenseVector), (count, features)) =>
          //requireValues(features)
          BLAS.axpy(1.0, features , featureSum)
          //BLAS.scal( 1.0, featureSum)
          (countSum + count, featureSum)
      },
      combOp = {
        case ((countSum1, featureSum1), (countSum2, featureSum2)) =>
          BLAS.axpy(1.5, featureSum2, featureSum1)
          //BLAS.scal( 0.5 , featureSum1)
          (countSum1 + countSum2,  featureSum1)
      }).collect().sortBy(_._1)

    // get number of classes
    val numLabels = aggregated.length

    //array contains classes
    val labelArray = new Array[Double](numLabels)

    // calculate covariance matrix per class (using Distributed Row Matrix)
    var i = 0
    var ResArr = new Array[Double](numFeatures * numFeatures)
    aggregated.foreach { case (label, (n, sumTermFreqs)) =>
      labelArray(i) = label

      /*
      val ds = dataset.select(col($(labelCol)), col($(featuresCol))).filter($(labelCol)+ "== " + label).rdd
        .map(   row =>  org.apache.spark.mllib.linalg.DenseVector.fromML( row.getAs[org.apache.spark.ml.linalg.Vector](1).toDense )   )

      val rowMatrix = new org.apache.spark.mllib.linalg.distributed.RowMatrix( ds.map(r=>r))

      */

      val ds = dataset.select(col($(labelCol)), col($(featuresCol)))
        .filter($(labelCol)+ "== " + label).rdd
        .map( row =>  row.getAs[org.apache.spark.ml.linalg.Vector](1).toDense )

      //.map(   row =>  org.apache.spark.mllib.linalg.DenseVector.fromML( row.getAs[org.apache.spark.ml.linalg.Vector](1).toDense )   )

      val rowMatrix = new org.apache.spark.mllib.linalg.distributed.RowMatrix( ds.map(r=> org.apache.spark.mllib.linalg.DenseVector.fromML(r)))
      var CovarianceMatrix = rowMatrix.computeCovariance()
      var TempArr = new Array[Double](numFeatures * numFeatures)
      TempArr = CovarianceMatrix.toArray.map(_ * (n - 1)  )
      ResArr = ResArr.zip(TempArr).map { case (x, y) => x + y }
      totalObservations = totalObservations + n
      i += 1
    }
    ResArr = ResArr.map(r => r / (totalObservations - numLabels))
    var util: LDAUtil = new LDAUtil()
    var MainCovMat = new org.apache.spark.mllib.linalg.DenseMatrix(numFeatures, numFeatures, ResArr)
    var MainCovarianceMatrix = new RowMatrix(util.matrixToRDD(MainCovMat, sc))
    InvMainCovarianceMatrix = util.computeInverse(MainCovarianceMatrix)

    // create map containg the mean vector for each class
    meanMap = aggregated.map( r => (r._1.toInt -> new DenseVector(r._2._2.toArray.map( e => e /r._2._1 )))).toMap

    //create map contains the probability (Nk/N) per each class
    ClassesProbMap = aggregated.map( r => (r._1.toInt -> r._2._1.toDouble / totalObservations)).toMap

    //create an LDA Model instance
    new LDAModel(uid, InvMainCovarianceMatrix , meanMap , ClassesProbMap , getscaled_data )}

  /**
    * Copy Extra PArameters
    * @param extra the extra parameter
    * @return
    */
  override def copy(extra: ParamMap): LDA = defaultCopy(extra)
}

object LDA extends DefaultParamsReadable[LDA] {

  override def load(path: String): LDA = super.load(path)
}




class LDAModel private[ml] (override val uid: String,
                            val invCovMatrix: org.apache.spark.mllib.linalg.DenseMatrix,
                            val classesMean: Map[Int, Vector] ,
                            val classesProb:Map[Int,Double] ,
                            val dataScaled: Boolean)

  extends ProbabilisticClassificationModel[Vector, LDAModel]
    with LDAParams with MLWritable {


  override val numFeatures: Int = invCovMatrix.numCols
  override val numClasses: Int = classesProb.keys.size

  override def write: MLWriter = new LDAModel.LDAModelWriter(this)

  override protected def predictRaw(features: Vector): Vector = {


    var Delta: Double = 0
    var rawPrediction: Vector = null
    var result: Array[Double] = Array.fill(numClasses)(0)

    //convert the point x to matrix  [x1, x2, x3, ...xn]
    var x = new org.apache.spark.mllib.linalg.DenseMatrix(1, numFeatures, features.toArray) //f.drop(1).dropRight(1).split(',').map(_.toDouble))

    //println("=====> point :"  + f.toString)

    // loop for each class and calculate the delta, chose class with highest delta
    for (k <- 0 to numClasses - 1) {

      // convert mean vector to DenseMatrix
      var meanmatrix = new org.apache.spark.mllib.linalg.DenseMatrix(numFeatures, 1, classesMean(k).toArray)

      //Get the mean matrix transepose
      var tran_meanmatrix = new org.apache.spark.mllib.linalg.DenseMatrix(1, numFeatures, classesMean(k).toArray)

      //calculate Delta
      val logvar: Double = math.log10(classesProb(k))
      val partonevar: Double = ((x.multiply(invCovMatrix)).multiply(meanmatrix)) (0, 0)
      val parttwovar: Double = 0.5 * (((tran_meanmatrix.multiply(invCovMatrix)).multiply(meanmatrix)) (0, 0))
      var Currdelta: Double = (partonevar - parttwovar + logvar)

      result(k) = Currdelta
    }
    rawPrediction = new DenseVector(result) //.map( e=> math.floor(e * 100) / 100))
    rawPrediction
  }


  override protected def raw2probabilityInPlace(rawPrediction: Vector): Vector ={
    var total = 0.0
    var negcomp = 0.0
    var deltas = rawPrediction.toArray

    negcomp = deltas.map( e => {
      if (e < 0 ) (- 2 * e)
      else 0
    }).sum

    deltas = deltas.map( e=> e + negcomp)

    total = deltas.sum
    // deltas = deltas.map(e => (e.toDouble /total.toDouble) * 100)
    deltas = deltas.map( e =>  (  (  e.toDouble /total.toDouble) * 100) )



    return new DenseVector(deltas)
  }

  override def copy(extra: ParamMap): LDAModel = {
    null
    //copyValues(new LDAModel(uid, pi, theta).setParent(this.parent), extra)
  }

}




object LDAModel extends MLReadable[LDAModel] {

  override def read: MLReader[LDAModel] = new LDAModelReader

  override def load(path: String): LDAModel = super.load(path)

  /** [[MLWriter]] instance for [[LDAModel]] */
  private[LDAModel] class LDAModelWriter(instance: LDAModel) extends MLWriter {

    private case class Data(pi: Vector, theta: Matrix)

    override protected def saveImpl(path: String): Unit = {
      /*
      // Save metadata and Params
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      // Save model data: pi, theta
      val data = Data(instance.pi, instance.theta)
      val dataPath = new Path(path, "data").toString
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.parquet(dataPath)
      */
    }
  }

  private class LDAModelReader extends MLReader[LDAModel] {

    /** Checked against metadata when loading model */
    private val className = classOf[LDAModel].getName

    override def load(path: String): LDAModel = {
      /*
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)

      val dataPath = new Path(path, "data").toString
      val data = sparkSession.read.parquet(dataPath)
      val vecConverted = MLUtils.convertVectorColumnsToML(data, "pi")
      val Row(pi: Vector, theta: Matrix) = MLUtils.convertMatrixColumnsToML(vecConverted, "theta")
        .select("pi", "theta")
        .head()
      val model = new NaiveBayesModel(metadata.uid, pi, theta)

      DefaultParamsReader.getAndSetParams(model, metadata)
      model
      */
      null
    }
  }


}
