import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import java.io.PrintWriter
import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.math.sqrt
import scala.util.Random

import my_utils.MyUtils

object distributed extends Serializable {

  def writeModelOnFile[T](model: Array[T], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    // we are printing to a file; therefore, parallelization would not improve performances
    model foreach({
      case seq: Seq[(String, (String, Double))] => seq foreach (el2 => {
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

    // import train and test datasets
    def train: BufferedSource = Source.fromFile("/media/alberto/5cc4e6c6-e71d-42b1-b6ec-bdfab5eabe1c/unibo/scp/MusicReccomendation/src/main/resources/train_100_10.txt")
    def test: BufferedSource = Source.fromFile("/media/alberto/5cc4e6c6-e71d-42b1-b6ec-bdfab5eabe1c/unibo/scp/MusicReccomendation/src/main/resources/test_100_10.txt")

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
    val (testUsersList, testUsersToSongsMap) = extractData(test)
    // convert mutable to immutable list
    val songsList: Seq[String] = mutSongs.toSeq
    // convert mutable to immutable map
    val songsToUsersMap = mutSongsToUsersMap.toMap

    // create users and songs RDD
    /*
    NOTE: you cannot map a RDD inside a transformation of another RDD (e.g. users.map(u=> songs.map(s=> ...))
    That's why we need usersList, songsList(:Seq[String]) AND users, songs(:RDD[String])
    */
    val testUsers = ctx.parallelize(testUsersList)
    val songs = ctx.parallelize(songsList)

    object ubmFunctions {
      // it calculates the cosine similarity between two users
      def cosineSimilarity(user1: String, user2: String) = {
        // Here, parallelization does not improve performances (TODO: check)
        val numerator = songsList.map(song =>
          // if both users listened to song return 1, else 0
          if (testUsersToSongsMap(user1).contains(song) && trainUsersToSongsMap(user2).contains(song)) 1 else 0
        ).sum
        // usersToSongMap(user).length represents how many songs the user listened to
        val denominator = sqrt(testUsersToSongsMap(user1).length) * sqrt(trainUsersToSongsMap(user2).length)
        if (denominator != 0) numerator / denominator else 0.0
      }

      def getModel(user: String) = {
        // foreach song, calculate the score for the user
        songsList.map(s => user -> (s, rank(user, s)))
      }

      def getModel2(song: String) = {
        // foreach song, calculate the score for the user
        testUsersList.map(u => u -> (song, rank(u, song)))
      }

      def rank (user: String, song: String) = {
        for {
          u2 <- trainUsers filter (u => u != user && trainUsersToSongsMap(u).contains(song))
        } yield {
          cosineSimilarity(user, u2)
        }
      } sum

    }

    object ibmFunctions {
      // it calculates the cosine similarity between two songs
      def cosineSimilarity(song1: String, song2: String): Double = {
        // Here, parallelization does not improve performances (TODO: check)
        val numerator = trainUsers.map(user => (
          // if the user listened to both songs return 1, else 0
          if (songsToUsersMap(song1).contains(user) && songsToUsersMap(song2).contains(user)) 1 else 0
          )).sum
        // pre-calculate denominator to catch if it is equal to 0
        val denominator = sqrt(songsToUsersMap(song1).length) * sqrt(songsToUsersMap(song2).length)
        if (denominator != 0) numerator / denominator else 0
      }

      def rank(user: String, song: String): Double = {
        // Here, parallelization does not improve performances (TODO: check)
        for {
          s2 <- songsList filter(s => s != song && songsToUsersMap(s).contains(user))
        } yield {
          cosineSimilarity(song, s2)
        }
      } sum

      def getModel(song: String) = {
        // foreach song, calculate the score for the user
        testUsersList.map(u => u -> (song, rank(u, song)))
      }

      def getModel2(user: String) = {
        // foreach song, calculate the score for the user
        songsList.map(s => user -> (s, rank(user, s)))
      }
    }

    val ubModel = MyUtils.time({
      // for each user, get user-based ranking
      val ubModel = testUsers.map(u => ubmFunctions.getModel(u))
      // save RDD on file
      ubModel.saveAsTextFile("DISTRIBUTED_OUTPUT/UBM")
      ubModel
    }, "(Distributed) user-based")
    val ubModel2 = MyUtils.time({
      val ubModel = songs.map(s => ubmFunctions.getModel2(s))
      ubModel.saveAsTextFile("DISTRIBUTED_OUTPUT/UBM2")
      ubModel
    }, "(Distributed) user-based 2")

    // TODO: probably we can iterate on users RDD instead of songs RDD (in this case we can delete the latter)
    val ibModel = MyUtils.time({
      // for each song, get item-based ranking
      val ibModel = songs.map(s => ibmFunctions.getModel(s))
      ibModel.saveAsTextFile("DISTRIBUTED_OUTPUT/IBM")
      ibModel
    }, "(Distributed) item-based")
    val ibModel2 = MyUtils.time({
      // for each song, get item-based ranking
      val ibModel = testUsers.map(u => ibmFunctions.getModel2(u))
      ibModel.saveAsTextFile("DISTRIBUTED_OUTPUT/IBM2")
      ibModel
    }, "(Distributed) item-based")

    val lcAlpha = 0.5
    val ubm = importModel("models/userBasedModel.txt")
    val ibm = importModel("models/itemBasedModel.txt")
    val lcModel = MyUtils.time({
      val modelsPair = ctx.parallelize(ubm.zip(ibm))
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      val lc = modelsPair.map {
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          (user1 -> (song1, rank1 * lcAlpha + rank2 * (1 - lcAlpha)))
      }
      lc.saveAsTextFile("DISTRIBUTED_OUTPUT/LCM")
      lc
    }, "(Distributed) linear combination")

    val itemBasedPercentage = 0.5
    val aModel = MyUtils.time({
      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      val modelsPair = ctx.parallelize(ubm.zip(ibm).zipWithIndex)
      val am = modelsPair.map({
        // for each pair
        case (couple, index) => couple match {
          case ((user1, song1, rank1), (user2, song2, rank2)) =>
            if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1))
        }
      })
      am.saveAsTextFile("DISTRIBUTED_OUTPUT/AM")
      am
    }, "(Distributed) aggregation model")

    val itemBasedProbability = 0.5
    val scModel = MyUtils.time({
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      val modelsPair = ctx.parallelize(ubm.zip(ibm))
      val scm = modelsPair.map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      })
      scm.saveAsTextFile("DISTRIBUTED_OUTPUT/SCM")
      scm
    }, "(Distributed) stochastic combination model")

    // writing on files
    writeModelOnFile(ubModel.collect(), "models/userBasedModelD.txt")
    writeModelOnFile(ibModel.collect(), "models/itemBasedModelD.txt")
    writeModelOnFile(lcModel.collect(), "models/linearCombinationModelD.txt")
    writeModelOnFile(aModel.collect(), "models/aggregationModelD.txt")
    writeModelOnFile(scModel.collect(), "models/stochasticCombinationModelD.txt")
  }
}
