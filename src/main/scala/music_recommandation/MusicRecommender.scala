package music_recommandation

import java.io._
import scala.collection.mutable
import scala.collection.immutable._
import scala.collection.parallel.CollectionConverters._
import scala.io._
import scala.language.postfixOps
import scala.math.sqrt
import scala.util.Random

class MusicRecommender(train: BufferedSource, test: BufferedSource) {

  /**
   * ******************************************************************************************
   * CLASS INITIALIZATION
   * ******************************************************************************************
   */

  /**
   * Read file containing the data storing them in their relative structures
   * @param in file containing the data
   * @return list of users in the file and map of list of songs for each user
   */
  private def extractData(in: BufferedSource): (List[String], Map[String, List[String]]) = {
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
        // update map with cases
        usersToSongsMap.updateWith(u) {
          // if user is already in the map, add song to the list of listened songs
          case Some(list: List[String]) => Some(list :+ s)
          // else add song to a new list related to the user
          case None => Some(List(s))
        }
        mutSongsToUsersMap.updateWith(s) {
          // if song is already in the map, add user to the list of users who listened to the song
          case Some(list: List[String]) => Some(list :+ u)
          // else add song to a new list related to the user
          case None => Some(List(u))
        }
      }
    }
    (usersInFile.toList, usersToSongsMap.toMap)
  }

  // store all songs from both files
  private val mutSongs = collection.mutable.Set[String]()
  // map song1->[{users who listened to song1}], ..., songN->[{users who listened to songN}]
  private val mutSongsToUsersMap = collection.mutable.Map[String, List[String]]()
  // get train and test data from files
  private val (trainUsers, trainUsersToSongsMap) = extractData(train)
  private val (testUsers, testUsersToSongsMap) = extractData(test)
  // convert mutable to immutable list
  private val songs: List[String] = mutSongs.toList
  // convert mutable to immutable map
  private val songsToUsersMap = mutSongsToUsersMap.toMap

  /**
   * ******************************************************************************************
   * CLASS METHODS
   * ******************************************************************************************
   */

  private def getModel(rank: (String, String) => Double): IterableOnce[(String, (String, Double))] = {
      for {
        u <- testUsers
        s <- songs
        if !testUsersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
      } yield u -> (s, rank(u, s))
  }

  private def getModelP(rank: (String, String) => Double): IterableOnce[(String, (String, Double))] = {
    // Main parallelization happens here
      for {
        u <- testUsers.iterator.toSeq.par
        s <- songs.iterator.toSeq.par
        if !testUsersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
      } yield u -> (s, rank(u, s))
  }

  def getUserBasedModel(parallel: Boolean = false): IterableOnce[(String, (String, Double))]  = {
    // it calculates the cosine similarity between two users
    def cosineSimilarity(user1: String, user2: String): Double = {
      val numerator = songs.iterator.toSeq.map(song =>
        // if both users listened to song return 1, else 0
        if (testUsersToSongsMap(user1).contains(song) && trainUsersToSongsMap(user2).contains(song)) 1 else 0
      ).sum
      // usersToSongMap(user).length represents how many songs the user listened to
      val denominator = sqrt(testUsersToSongsMap(user1).length) * sqrt(trainUsersToSongsMap(user2).length)
      if (denominator != 0) numerator / denominator else 0.0
    }

    def rank(user: String, song: String): Double = {
      for {
        u2 <- trainUsers
        if u2 != user && trainUsersToSongsMap(u2).contains(song)
      } yield {
        cosineSimilarity(user, u2)
      }
    } sum

    // Calculate model
    if (parallel)
      getModelP(rank)
    else
      getModel(rank)
  }

  def getItemBasedModel(parallel: Boolean = false): IterableOnce[(String, (String, Double))] = {
    // it calculates the cosine similarity between two songs
    def cosineSimilarity(song1: String, song2: String): Double = {
      // Here, parallelization does not improve performances (TODO: check)
      val numerator = trainUsers.iterator.map(user =>
          // if the user listened to both songs return 1, else 0
          if (songsToUsersMap(song1).contains(user) && songsToUsersMap(song2).contains(user)) 1 else 0
      ).sum
      // pre-calculate denominator to catch if it is equal to 0
      val denominator = sqrt(songsToUsersMap(song1).length) * sqrt(songsToUsersMap(song2).length)
      if (denominator != 0) numerator / denominator else 0
    }

    def rank(user: String, song: String): Double = {
      // Here, parallelization does not improve performances (TODO: check)
      for {
        s2 <- songs
        if s2 != song
        if testUsersToSongsMap(user).contains(s2)
      } yield {
        cosineSimilarity(song, s2)
      }
    } sum

    // Calculate model
    if (parallel)
      getModelP(rank)
    else
      getModel(rank)
  }

  def getLinearCombinationModel(ubm: List[(String, String, Double)],
                                ibm: List[(String, String, Double)],
                                alpha: Double,
                                parallel: Boolean = false): IterableOnce[(String, (String, Double))] = {

    if (parallel) {
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).par.map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
      })
    } else {
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
      })
    }

  }

  def getAggregationModel(ubm: List[(String, String, Double)], ibm: List[(String, String, Double)],
                          itemBasedPercentage: Double = 0.5,
                          parallel: Boolean = false): IterableOnce[(String, (String, Double))] = {

    // Exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedPercentage < 0 || itemBasedPercentage > 1) {
      System.err.println("Percentage must be between 0 and 1\n");
      System.exit(-1);
    }

    if (parallel) {
      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      // TODO: find a better solution than zipWithIndex (may be a slow solution)
      ubm.zip(ibm).zipWithIndex.par.map({
        // for each pair
        case (couple, index) => couple match {
          case ((user1, song1, rank1), (user2, song2, rank2)) =>
            if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1));
        }
      })
    } else {
      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      // TODO: find a better solution than zipWithIndex (may be a slow solution)
      ubm.zip(ibm).zipWithIndex.map({
        // for each pair
        case (couple, index) => couple match {
          case ((user1, song1, rank1), (user2, song2, rank2)) =>
            if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1))
        }
      })
    }

  }

  def getStochasticCombinationModel(ubm: List[(String, String, Double)],
                                    ibm: List[(String, String, Double)],
                                    itemBasedProbability: Double = 0.5,
                                    parallel: Boolean = false): IterableOnce[(String, (String, Double))] = {

    // Exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedProbability < 0 || itemBasedProbability > 1) {
      System.err.println("Probability must be between 0 and 1\n");
      System.exit(-1);
    }

    if (parallel) {
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).par.map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      })
    } else {
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      })
    }

  }

  def writeModelOnFile(model: IterableOnce[(String, (String, Double))], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    // we are printing to a file; therefore, parallelization would not improve performances
    model.iterator foreach (el => {
      out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
    })
    out.close()
  }

  def importModelFromFile(pathToModel: String): List[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)

    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.IeeeOrdering.reverse)
    val model = modelFile.getLines().toList map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }

}