import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import java.io.{PrintWriter, Serializable}
import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.math.sqrt
import scala.util.Random
import my_utils.MyUtils

import scala.collection.GenSeq
import scala.collection.parallel.ParSeq

object distributed extends Serializable  {

  def writeModelOnFile[T](model: Array[T], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    // we are printing to a file; therefore, parallelization would not improve performances
    model foreach({
      case seq: Seq[(String, (String, Double))] =>
        seq foreach (el2 => {
          out.write(s"${el2._1}\t${el2._2._1}\t${el2._2._2}\n")
        })
      case tuple: (String, (String, Double)) =>
        out.write(s"${tuple._1}\t${tuple._2._1}\t${tuple._2._2}\n")
    })
    out.close()
  }

  private def importModel(pathToModel: String): Seq[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)

    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.reverse)
    val model = modelFile.getLines().toList map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }
  def main(args: Array[String]): Unit = {

    def trainUsersN: Integer = 100
    def testUsersN: Integer = 10

    // import train and test datasets
    def train: BufferedSource = Source.fromResource(s"train_${trainUsersN}_${testUsersN}.txt")
    def test: BufferedSource = Source.fromResource(s"test_${trainUsersN}_${testUsersN}.txt")
    def testLabelsFile: BufferedSource = Source.fromResource(s"test_labels_${trainUsersN}_${testUsersN}.txt")

    // instantiate spark context
    val conf = new SparkConf().setAppName("MusicRecommendation").setMaster("local[*]")
    val ctx = new SparkContext(conf)

    // store all songs from both files
    val mutSongs = collection.mutable.Set[String]()
    // map song1->[{users who listened to song1}], ..., songN->[{users who listened to songN}]
    val mutSongsToUsersMap = collection.mutable.Map[String, List[String]]()

    def extractData(in: BufferedSource): (Seq[String], Map[String, List[String]]) = {
      // all users in file
      val usersInFile = collection.mutable.Set[String]()
      // map user1->[{songs listened by user1}], ..., userN->[{songs listened by userN}]
      val usersToSongsMap = collection.mutable.Map[String, List[String]]()
      // for each split line on "\t"
      for {
        line <- in.getLines().toList
      } yield line split "\t" match {
        case Array(u, s, _) => {
          // add user and song
          usersInFile add u
          mutSongs add s
          // update maps
          usersToSongsMap.update(u, s :: usersToSongsMap.getOrElse(u, Nil))
          mutSongsToUsersMap.update(s, u :: mutSongsToUsersMap.getOrElse(s, Nil))
        }
      }
      (usersInFile.toSeq, usersToSongsMap.toMap)
    }

    // get train and test data from files
    val (trainUsers, trainUsersToSongsMap) = extractData(train)
    val (testUsers, testUsersToSongsMap) = extractData(test)
    // convert mutable to immutable list
    val songs: Seq[String] = mutSongs.toSeq
    // convert mutable to immutable map
    val songsToUsersMap = mutSongsToUsersMap.toMap

    println("Songs: " + songs.length)

    def importTestLabels(in: BufferedSource): (Map[String, List[String]], List[String]) = {
      val testLabels = collection.mutable.Map[String, List[String]]()
      val newSongs = collection.mutable.Set[String]()
      // for each split line on "\t"
      for {
        line <- in.getLines().toList
      } yield line split "\t" match {
        case Array(u, s, _) => {
          // users are the same as in testUsers, while testLabels could contain new songs
          newSongs add s
          // update map
          testLabels.update(u, s :: testLabels.getOrElse(u, Nil))
        }
      }
      (testLabels.toMap, newSongs.toList)
    }

    // validation data (import just once at initialization)
    val (testLabels, newSongs) = importTestLabels(testLabelsFile)
    val newSongsRdd = ctx.parallelize(newSongs)
    object UserBasedModel {
      // calculate the cosine similarity between two users
      def cosineSimilarity(user1: String, user2: String): Double = {
        // number of song listened by both users
        val numerator = songs.map(song =>
          // if both users listened to song return 1, else 0
          if (testUsersToSongsMap(user1).contains(song) && trainUsersToSongsMap(user2).contains(song)) 1 else 0
        ).sum
        // usersToSongMap(user).length represents how many songs the user listened to
        val denominator = sqrt(testUsersToSongsMap(user1).length) * sqrt(trainUsersToSongsMap(user2).length)
        if (denominator != 0) numerator / denominator else 0.0
      }

      def getRanks1(user: String):ParSeq[(String, (String, Double))] = {
        // foreach song, calculate the score for the user
        for {
          song <- songs.iterator.toSeq.par filter (song => !testUsersToSongsMap(user).contains(song))
        } yield {
          user -> (song, rank(user, song))
        }
      }

      def getRanks2(song: String): ParSeq[(String, (String, Double))] = {
        // foreach user, calculate the score for the user
        for {
          user <- testUsers.iterator.toSeq.par filter (user => !testUsersToSongsMap(user).contains(song))
        } yield {
          user -> (song, rank(user, song))
        }
      }

      def rank(user: String, song: String): Double = {
        // sum the cosine similarities between the user and each user in the training set that listened to the song
        for {
          user2 <- trainUsers filter (user2 => trainUsersToSongsMap(user2).contains(song))
        } yield {
          cosineSimilarity(user, user2)
        }
      } sum
    }

    object ItemBasedModel {
      // calculate the cosine similarity between two songs
      def cosineSimilarity(song1: String, song2: String): Double = {
        val numerator = trainUsers.map(user => (
          // if the user listened to both songs return 1, else 0
          if (songsToUsersMap(song1).contains(user) && songsToUsersMap(song2).contains(user)) 1 else 0
          )).sum
        // pre-calculate denominator to catch if it is equal to 0
        val denominator = sqrt(songsToUsersMap(song1).length) * sqrt(songsToUsersMap(song2).length)
        if (denominator != 0) numerator / denominator else 0
      }

      def rank(user: String, song: String): Double = {
        // sum the cosine similarities between the song and each other song the user listened to
        for {
          song2 <- songs filter(song2 => song2 != song && songsToUsersMap(song2).contains(user))
        } yield {
          cosineSimilarity(song, song2)
        }
      } sum

      def getRanks1(user: String): ParSeq[(String, (String, Double))] = {
        // foreach song, calculate the score for the user
        for {
          s <- songs.iterator.toSeq.par filter (s => !testUsersToSongsMap(user).contains(s))
        } yield {
          user -> (s, rank(user, s))
        }
      }

      def getRanks2(song: String): ParSeq[(String, (String, Double))] = {
        // foreach user, calculate the score for the user
        for {
          user <- testUsers.iterator.toSeq.par filter (user => !testUsersToSongsMap(user).contains(song))
        } yield {
          user -> (song, rank(user, song))
        }
      }
    }

    object evaluation_functions extends Serializable {
      /**
       * Convert the prediction scores to class labels
       *
       * @param model     the predictions scores for test users
       * @param threshold _ > threshold = 1, _ <= threshold = 0 (default 0.0)
       * @return map of list of songs the user will listen predicted by the model for each user
       */
      def predictionToClassLabels(model: Array[(String,(String, Double))], threshold: Double = 0.0): Map[String, List[String]] = {
        // @model contains the prediction scores for test users
        val predictions = collection.mutable.Map[String, List[String]]()
        val min = model.map(el => (el._2._2)).min
        val max = model.map(el => (el._2._2)).max

        model foreach (el => {
          // values can be greater than 1, therefore normalization is advised
          if ((el._2._2 - min) / (max - min) > threshold) predictions.update(el._1, el._2._1 :: predictions.getOrElse(el._1, Nil))
        })
        predictions.toMap
      }

      def confusionMatrix(predictions: Map[String, List[String]], song: String): (Int, Int, Int, Int) = {
        // NB: if threshold too high, user could not be in predictions!
        testUsers.map(user => (
          // True positives
          if (predictions.contains(user) && predictions(user).contains(song) && testLabels(user).contains(song)) 1 else 0,
          // False positives
          if (predictions.contains(user) && predictions(user).contains(song) && !testLabels(user).contains(song)) 1 else 0,
          // True negatives
          if ((!predictions.contains(user) || !predictions(user).contains(song)) && !testLabels(user).contains(song)) 1 else 0,
          // False negatives
          if ((!predictions.contains(user) || !predictions(user).contains(song)) && testLabels(user).contains(song)) 1 else 0
        )).fold((0, 0, 0, 0)) { (acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3, acc._4 + tup._4) }
      }

      /**
       * Calculate precision = TP / (TP + FP)
       */
      def precision(confusionMatrix: (Int, Int, Int, Int)): Double = {
        if (confusionMatrix._1 + confusionMatrix._2 > 0.0)
          confusionMatrix._1.toDouble / (confusionMatrix._1 + confusionMatrix._2)
        else
          0.0
      }

      /**
       * Calculate recall = TP / (TP + FN)
       */
      def recall(confusionMatrix: (Int, Int, Int, Int)): Double = {
        if (confusionMatrix._1 + confusionMatrix._4 > 0.0)
          confusionMatrix._1.toDouble / (confusionMatrix._1 + confusionMatrix._4)
        else
          0.0
      }

      def averagePrecision(model: Array[(String, (String, Double))]): RDD[(String, Double)] = {
        val thresholds = 0.0 :: 0.1 :: 0.2 :: 0.3 :: 0.4 :: 0.5 :: 0.6 :: 0.7 :: 0.8 :: 0.9 :: 1.0 :: Nil
        // predictions = (threshold) -> (user -> list of songs)
        val predictions = for {t <- thresholds} yield {
          predictionToClassLabels(model, t)
        }

        // TODO: evaluate recalls (and eventually precisions) beforehand to spare O(length of thresholds) calls to recall and confusionMatrix
        def singleAveragePrecision(song: String): Double = {
          thresholds.zipWithIndex.map(t =>
            if (t._2 == (thresholds.length - 1)) {
              0.0
            }
            else if (t._2 == (thresholds.length - 2)) {
              (recall(confusionMatrix(predictions(t._2), song)) - 0.0) * precision(confusionMatrix(predictions(t._2), song))
            } else
              (recall(confusionMatrix(predictions(t._2), song)) - recall(confusionMatrix(predictions(t._2 + 1), song))) * precision(confusionMatrix(predictions(t._2), song))
          ).sum
        }

         newSongsRdd map (song => (song, singleAveragePrecision(song)))
      }

      def meanAveragePrecision(model: Array[(String, (String, Double))]): Double = {
        averagePrecision(model).map(ap => ap._2).sum / newSongs.length
      }

      def evaluateModel(model: Array[(String, (String, Double))]): Double = {
        // @model contains the prediction scores for test users
        meanAveragePrecision(model)
      }
    }

    // Version distributing over users (internal parallelization over songs)
    def getUserBasedModel1: Array[(String, (String, Double))] = {
      ctx.parallelize(testUsers).map(user => UserBasedModel.getRanks1(user).seq).collect.flatten
    }

    // Version distributing over songs (internal parallelization over users)
    def getUserBasedModel2: Array[(String, (String, Double))] = {
      ctx.parallelize(songs).map(song => UserBasedModel.getRanks2(song).seq).collect.flatten
    }

    // Version distributing over users (internal parallelization over songs)
    def getItemBasedModel1: Array[(String, (String, Double))] = {
      ctx.parallelize(testUsers).map(user => ItemBasedModel.getRanks1(user).seq).collect.flatten
    }

    // Version distributing over songs (internal parallelization over users)
    def getItemBasedModel2: Array[(String, (String, Double))] = {
      ctx.parallelize(songs).map(song => ItemBasedModel.getRanks2(song).seq).collect.flatten
    }

    // songs >> users, nodes << cores per node (or songs << users, nodes >> cores per node)
    val ubModel = MyUtils.time(getUserBasedModel1, "(Distributed) user-based")
    val ibModel = MyUtils.time(getItemBasedModel1, "(Distributed) item-based")

    // songs >> users, nodes >> cores per node (or songs << users, nodes << cores per node)
    //val ubModel = MyUtils.time(getUserBasedModel2, "(Distributed) user-based")
    //val ibModel = MyUtils.time(getItemBasedModel2, "(Distributed) item-based")

    /*
    writeModelOnFile(ubModel, "models/userBasedModelD.txt")
    writeModelOnFile(ibModel, "models/itemBasedModelD.txt")
    */

    //val ubm = importModel("models/userBasedModelD.txt")
    //val ibm = importModel("models/itemBasedModelD.txt")
    val ordering = Ordering.Tuple2(Ordering.String, Ordering.Tuple2(Ordering.String, Ordering.Double.reverse))
    val ubm = ubModel sorted ordering
    val ibm = ibModel sorted ordering

    def getLinearCombinationModel(ubmModel: Array[(String, (String, Double))],
                                  ibmModel: Array[(String, (String, Double))],
                                  lcAlpha: Double = 0.5): Array[(String, (String, Double))] = {

      ctx.parallelize(ubmModel.zip(ibmModel)) map {
        // for each pair
        case ((user1, (song1, rank1)), (user2, (song2, rank2))) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          (user1 -> (song1, rank1 * lcAlpha + rank2 * (1 - lcAlpha)))
      } collect()
    }

    val lcModel = MyUtils.time(getLinearCombinationModel(ubm, ibm), "(Distributed) linear combination")

    def getAggregationModel(ubmModel: Array[(String, (String, Double))],
                            ibmModel: Array[(String, (String, Double))],
                            itemBasedPercentage: Double = 0.5): Array[(String, (String, Double))] = {

      val length = ubmModel.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      ctx.parallelize(ubmModel.zip(ibmModel).zipWithIndex) map{
        // for each pair
        case (couple, index) => couple match {
          case ((user1, (song1, rank1)), (user2, (song2, rank2))) =>
            if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1))
        }
      } collect()
    }

    val aModel = MyUtils.time(getAggregationModel(ubm, ibm), "(Distributed) aggregation model")

    val itemBasedProbability = 0.5
    val scModel = MyUtils.time({
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      val modelsPair = ctx.parallelize(ubm.zip(ibm))
      val scm = modelsPair.map({
        // for each pair
        case ((user1, (song1, rank1)), (user2, (song2, rank2))) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      })
      //scm.saveAsTextFile("DISTRIBUTED_OUTPUT/SCM")
      scm.collect()
    }, "(Distributed) stochastic combination model")

    // writing on files
//    writeModelOnFile(ubModel, "models/userBasedModelD.txt")
//    writeModelOnFile(ibModel, "models/itemBasedModelD.txt")
//    writeModelOnFile(lcModel, "models/linearCombinationModelD.txt")
//    writeModelOnFile(aModel, "models/aggregationModelD.txt")
//    writeModelOnFile(scModel, "models/stochasticCombinationModelD.txt")

    // models evaluation
    println("(Distributed) user-based model mAP: " + evaluation_functions.evaluateModel(ubModel))
    println("(Distributed) item-based model mAP: " + evaluation_functions.evaluateModel(ibModel))
    println("(Distributed) linear-combination model mAP: " + evaluation_functions.evaluateModel(lcModel))
    println("(Distributed) aggregation model mAP: " + evaluation_functions.evaluateModel(aModel))
    println("(Distributed) stochastic model mAP: " + evaluation_functions.evaluateModel(scModel))
  }
}
