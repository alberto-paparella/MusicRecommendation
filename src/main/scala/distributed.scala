import org.apache.spark.{SparkConf, SparkContext}

import java.io.PrintWriter
import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.math.sqrt

object distributed extends Serializable {

  def time[R](block: => R, operation: String = "unknown"): R = {
    // get start time
    val t0 = System.nanoTime()
    // execute code
    val result = block
    // get end time
    val t1 = System.nanoTime()
    // print elapsed time
    println(s"\n\n\nElapsed time for $operation:\t" + (t1 - t0) / 1000000 + "ms\n\n\n")
    // return the result
    result
  }

  def writeModelOnFile(model: Array[Seq[(String, (String, Double))]], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    // we are printing to a file; therefore, parallelization would not improve performances
    model.iterator foreach (el => el.map(el2 => {
      out.write(s"${el2._1}\t${el2._2._1}\t${el2._2._2}\n")
    }))
    out.close()
  }

  private def importModel(pathToModel: String): Seq[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)

    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.IeeeOrdering.reverse)
    val model = modelFile.getLines().toList map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }
  def main(args: Array[String]): Unit = {
    val fileName: String = "/home/gabbo/University/Magistrale/scpproject/MusicReccomendation/src/main/resources/train_triplets_2k.txt"
    def in: BufferedSource = Source.fromFile(fileName)
    val conf = new SparkConf().setAppName("MusicRecommendation").setMaster("local[*]")
    val ctx = new SparkContext(conf)

    def songsByUser(): (Seq[String], Seq[String], Map[String, List[String]], Map[String, List[String]]) = {
      // all users in file
      val usersInFile = collection.mutable.Set[String]()
      // all songs in file
      val songsInFile = collection.mutable.Set[String]()
      // map user1->[{songs listened by user1}], ..., userN->[{songs listened by userN}]
      val songsUsersMap = collection.mutable.Map[String, List[String]]()
      // map song1->[{users who listened to song1}], ..., songN->[{users who listened to songN}]
      val usersSongsMap = collection.mutable.Map[String, List[String]]()
      // for each split line on "\t"
      for {
        line <- in.getLines().toList
      } yield line split "\t" match {
        case Array(u, s, _) => {
          // add user and song
          usersInFile add u
          songsInFile add s
          // update map with cases
          songsUsersMap.updateWith(u) {
            // if user is already in the map, add song to the list of listened songs
            case Some(list: List[String]) => Some(list :+ s)
            // else add song to a new list related to the user
            case None => Some(List(s))
          }
          usersSongsMap.updateWith(s) {
            // if song is already in the map, add user to the list of users who listened to the song
            case Some(list: List[String]) => Some(list :+ u)
            // else add song to a new list related to the user
            case None => Some(List(u))
          }
        }
      }
      (usersInFile.toSeq, songsInFile.toSeq, songsUsersMap.toMap, usersSongsMap.toMap)
    }

    // get data from file
    val (usersList, songsList, usersToSongsMap, songsToUsersMap) = songsByUser()

    // create users and songs RDD
    /*
    NOTE: you cannot map a RDD inside a transformation of another RDD (e.g. users.map(u=> songs.map(s=> ...))
    That's why we need usersList, songsList(:Seq[String]) AND users, songs(:RDD[String])
    */
    val users = ctx.parallelize(usersList)
    val songs = ctx.parallelize(songsList)

    object ubmFunctions {
      // it calculates the cosine similarity between two users
      def cosineSimilarity(user1: String, user2: String) = {
        // Here, parallelization does not improve performances (TODO: check)
        val numerator = songsList.map(song =>
          // if both users listened to song return 1, else 0
          if (usersToSongsMap(user1).contains(song) && usersToSongsMap(user2).contains(song)) 1 else 0
        ).sum
        // usersToSongMap(user).length represents how many songs the user listened to
        val denominator = sqrt(usersToSongsMap(user1).length) * sqrt(usersToSongsMap(user2).length)
        if (denominator != 0) numerator / denominator else 0.0
      }

      def getModel(user: String) = {
        // foreach song, calculate the score for the user
        songsList.map(s => user -> (s, rank(user, s)))
      }

      def rank (user: String, song: String) = {
        for {
          u2 <- usersList filter (u => u != user && usersToSongsMap(u).contains(song))
        } yield {
          cosineSimilarity(user, u2)
        }
      } sum

    }

    object ibmFunctions {
      // it calculates the cosine similarity between two songs
      def cosineSimilarity(song1: String, song2: String): Double = {
        // Here, parallelization does not improve performances (TODO: check)
        val numerator = usersList.map(user => (
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
        usersList.map(u => u -> (song, rank(u, song)))
      }
    }

    val ubModel = time({
      // for each user, get user-based ranking
      val ubModel = users.map(u => ubmFunctions.getModel(u))
      // save RDD on file
      ubModel.saveAsTextFile("DISTRIBUTED_OUTPUT/UBM")
      ubModel
    }, "(Distributed) user-based")

    // TODO: probably we can iterate on users RDD instead of songs RDD (in this case we can delete the latter)
    val ibModel = time({
      // for each song, get item-based ranking
      val ibModel = songs.map(s => ibmFunctions.getModel(s))
      ibModel.saveAsTextFile("DISTRIBUTED_OUTPUT/IBM")
      ibModel
    }, "(Distributed) item-based")

    val lcAlpha = 0.5
    val ubm = importModel("models/userBasedModel.txt")
    val ibm = importModel("models/itemBasedModel.txt")
    val lcModel = time({
      val modelsPair = ctx.parallelize(ubm.zip(ibm))
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      val lc = modelsPair.map {
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          (user1 -> (song1, rank1 * lcAlpha + rank2 * (1 - lcAlpha)))
      }
      // TODO: it does not save correctly
      lc.saveAsTextFile("DISTRIBUTED_OUTPUT/LCM")
      lc
    }, "(Distributed) linear combination")

    // writing on files
    writeModelOnFile(ubModel.collect(), "models/userBasedModelD.txt")
    writeModelOnFile(ibModel.collect(), "models/itemBasedModelD.txt")
//    writeModelOnFile(lcModel.collect(), "models/linearCombinationModelD.txt")
  }
}
