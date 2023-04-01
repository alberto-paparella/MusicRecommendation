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

    // calculating models (both sequential and parallel)
    val userBasedModel = musicRecommender.getUserBasedModel(parallel=false)
    val userBasedModelP = musicRecommender.getUserBasedModel(parallel=true)
    val itemBasedModel = musicRecommender.getItemBasedModel(parallel = false)
    val itemBasedModelP = musicRecommender.getItemBasedModel(parallel = true)

    // saving user-based model (both sequential and parallel)
    musicRecommender.writeModelOnFile(userBasedModel, "models/userBasedModel.txt")
    musicRecommender.writeModelOnFile(userBasedModelP, "models/userBasedModelP.txt")
    musicRecommender.writeModelOnFile(itemBasedModel, "models/itemBasedModel.txt")
    musicRecommender.writeModelOnFile(itemBasedModelP, "models/itemBasedModelP.txt")
  }
}
