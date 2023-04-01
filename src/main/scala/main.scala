import music_recommandation.MusicRecommender

import scala.collection.parallel.CollectionConverters._
import scala.io._
import scala.language._

object main {
  def main(args: Array[String]): Unit = {

    // verbosity of the output (true = debugging, false = execution)
    val verbose = true

    // import train and test datasets
    def train: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("train_100_10.txt").getPath)
    def test: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("test_100_10.txt").getPath)
    if (verbose) println("Loaded files")

    // instantiate musicRecommender
    val musicRecommender: MusicRecommender = new MusicRecommender(train, test)
    if (verbose) println("MusicRecommender instanced")

    /*
    // calculating models (both sequential and parallel)
    val userBasedModel = musicRecommender.getUserBasedModel(parallel=false)
    val userBasedModelP = musicRecommender.getUserBasedModel(parallel=true)
    val itemBasedModel = musicRecommender.getItemBasedModel(parallel = false)
    val itemBasedModelP = musicRecommender.getItemBasedModel(parallel = true)

    // saving models to file (both sequential and parallel)
    musicRecommender.writeModelOnFile(userBasedModel, "models/userBasedModel.txt")
    musicRecommender.writeModelOnFile(userBasedModelP, "models/userBasedModelP.txt")
    musicRecommender.writeModelOnFile(itemBasedModel, "models/itemBasedModel.txt")
    musicRecommender.writeModelOnFile(itemBasedModelP, "models/itemBasedModelP.txt")
     */

    // importing models from file (in case you wanna skip/separate execution wrt ubm and ibm
    val userBasedModel = musicRecommender.importModelFromFile("models/userBasedModel.txt")
    val userBasedModelP = musicRecommender.importModelFromFile("models/userBasedModelP.txt")
    val itemBasedModel = musicRecommender.importModelFromFile("models/itemBasedModel.txt")
    val itemBasedModelP = musicRecommender.importModelFromFile("models/itemBasedModelP.txt")

    // calculating combination models (both sequential and parallel)
    val linearCombinationModel = musicRecommender.getLinearCombinationModel(userBasedModel, itemBasedModel, 0.5, parallel=false)
    val linearCombinationModelP = musicRecommender.getLinearCombinationModel(userBasedModelP, itemBasedModelP, 0.5, parallel = true)
    val aggregationModel = musicRecommender.getAggregationModel(userBasedModel,itemBasedModel,0.5,parallel=false)
    val aggregationModelP = musicRecommender.getAggregationModel(userBasedModelP, itemBasedModelP, 0.5, parallel = true)
    val stochasticCombinationModel = musicRecommender.getStochasticCombinationModel(userBasedModel,itemBasedModel,0.5,parallel=false)
    val stochasticCombinationModelP = musicRecommender.getStochasticCombinationModel(userBasedModelP,itemBasedModelP,0.5,parallel=true)

    // saving models to file (both sequential and parallel)
    musicRecommender.writeModelOnFile(linearCombinationModel, "models/linearCombinationModel.txt")
    musicRecommender.writeModelOnFile(linearCombinationModelP, "models/linearCombinationModelP.txt")
    musicRecommender.writeModelOnFile(aggregationModel, "models/aggregationModel.txt")
    musicRecommender.writeModelOnFile(aggregationModelP, "models/aggregationModelP.txt")
    musicRecommender.writeModelOnFile(stochasticCombinationModel, "models/stochasticCombinationModel.txt")
    musicRecommender.writeModelOnFile(stochasticCombinationModelP, "models/stochasticCombinationModelP.txt")
  }
}
