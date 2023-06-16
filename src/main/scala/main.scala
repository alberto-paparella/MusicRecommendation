import music_recommandation.MusicRecommender
import my_utils.MyUtils

import scala.collection.GenSeq
import scala.io._
import scala.language._

object main {
  def main(args: Array[String]): Unit = {

    // verbosity of the output (true = debugging, false = execution)
    val verbose = true

    // import train and test datasets
    def train: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("train_100_10.txt").getPath)
    def test: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("test_100_10.txt").getPath)
    def testLabels: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("test_labels_100_10.txt").getPath)
    if (verbose) println("Loaded files")

    // instantiate musicRecommender
    val musicRecommender: MusicRecommender = new MusicRecommender(train, test, testLabels)
    if (verbose) println("MusicRecommender instanced")

    // calculating models (both sequential and parallel)
    val (
      userBasedModel: GenSeq[GenSeq[(String, (String, Double))]],
      userBasedModelP: GenSeq[GenSeq[(String, (String, Double))]],
      itemBasedModel: GenSeq[GenSeq[(String, (String, Double))]],
      itemBasedModelP: GenSeq[GenSeq[(String, (String, Double))]]
      ) = if (verbose) (
        MyUtils.time(musicRecommender.getUserBasedModel(parallel=false), "(Sequential) user-based model"),
        MyUtils.time(musicRecommender.getUserBasedModel(parallel=true), "(Parallel) user-based model"),
        MyUtils.time(musicRecommender.getItemBasedModel(parallel = false), "(Sequential) item-based model"),
        MyUtils.time(musicRecommender.getItemBasedModel(parallel = true), "(Parallel) item-based model")
      ) else (
        musicRecommender.getUserBasedModel(parallel=false),
        musicRecommender.getUserBasedModel(parallel=true),
        musicRecommender.getItemBasedModel(parallel = false),
        musicRecommender.getItemBasedModel(parallel = true)
      )

    // saving models to file (both sequential and parallel)
    musicRecommender.writeModelOnFile(userBasedModel.flatten, "models/userBasedModel.txt")
    musicRecommender.writeModelOnFile(userBasedModelP.flatten, "models/userBasedModelP.txt")
    musicRecommender.writeModelOnFile(itemBasedModel.flatten, "models/itemBasedModel.txt")
    musicRecommender.writeModelOnFile(itemBasedModelP.flatten, "models/itemBasedModelP.txt")

    // importing models from file (in case you wanna skip/separate execution wrt ubm and ibm
    val ubm = musicRecommender.importModelFromFile("models/userBasedModel.txt")
    val ibm = musicRecommender.importModelFromFile("models/itemBasedModel.txt")

    // calculating combination models (both sequential and parallel)
    val (
      linearCombinationModel: GenSeq[(String, (String, Double))],
      linearCombinationModelP: GenSeq[(String, (String, Double))],
      aggregationModel: GenSeq[(String, (String, Double))],
      aggregationModelP: GenSeq[(String, (String, Double))],
      stochasticCombinationModel: GenSeq[(String, (String, Double))],
      stochasticCombinationModelP: GenSeq[(String, (String, Double))]
      ) = if (verbose) (
        MyUtils.time(musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel=false),
          "(Sequential) linear-combination model"),
        MyUtils.time(musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel=true),
          "(Parallel) linear-combination model"),
        MyUtils.time(musicRecommender.getAggregationModel(ubm, ibm,0.5,parallel=false),
          "(Sequential) aggregation model"),
        MyUtils.time( musicRecommender.getAggregationModel(ubm, ibm,0.5,parallel=true),
          "(Parallel) aggregation model"),
        MyUtils.time(musicRecommender.getStochasticCombinationModel(ubm, ibm,0.5,parallel=false),
          "(Sequential) stochastic-combination model"),
        MyUtils.time(musicRecommender.getStochasticCombinationModel(ubm, ibm, 0.5, parallel = true),
          "(Parallel) stochastic-combination model")
      ) else (
        musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel = true),
        musicRecommender.getAggregationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getAggregationModel(ubm, ibm, 0.5, parallel = true),
        musicRecommender.getStochasticCombinationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getStochasticCombinationModel(ubm, ibm, 0.5, parallel = true)
      )

    // saving models to file (both sequential and parallel)
    musicRecommender.writeModelOnFile(linearCombinationModel, "models/linearCombinationModel.txt")
    musicRecommender.writeModelOnFile(linearCombinationModelP, "models/linearCombinationModelP.txt")
    musicRecommender.writeModelOnFile(aggregationModel, "models/aggregationModel.txt")
    musicRecommender.writeModelOnFile(aggregationModelP, "models/aggregationModelP.txt")
    musicRecommender.writeModelOnFile(stochasticCombinationModel, "models/stochasticCombinationModel.txt")
    musicRecommender.writeModelOnFile(stochasticCombinationModelP, "models/stochasticCombinationModelP.txt")

    // evaluating models; mAP should be the same between sequential and parallel, except for stochasticCombinationModel
    println("(Sequential) user-based model mAP: " + musicRecommender.evaluateModel(userBasedModel.flatten))
    println("(Parallel) user-based model mAP: " + musicRecommender.evaluateModel(userBasedModelP.flatten))
    println("(Sequential) item-based model mAP: " + musicRecommender.evaluateModel(itemBasedModel.flatten))
    println("(Parallel) item-based model mAP: " + musicRecommender.evaluateModel(itemBasedModelP.flatten))
    println("(Sequential) linear-combination model mAP: " + musicRecommender.evaluateModel(linearCombinationModel))
    println("(Parallel) linear-combination model mAP: " + musicRecommender.evaluateModel(linearCombinationModelP))
    println("(Sequential) aggregation model model mAP: " + musicRecommender.evaluateModel(aggregationModel))
    println("(Parallel) aggregation model model mAP: " + musicRecommender.evaluateModel(aggregationModelP))
    println("(Sequential) stochastic-combination model mAP: " + musicRecommender.evaluateModel(stochasticCombinationModel))
    println("(Parallel) stochastic-combination model mAP: " + musicRecommender.evaluateModel(stochasticCombinationModelP))
  }
}