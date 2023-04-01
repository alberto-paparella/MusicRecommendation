package music_recommandation

import my_utils.MyUtils

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
      MyUtils.time(getModelP(rank), "(Parallel) user-based model")
    else
      MyUtils.time(getModel(rank), "(Sequential) user-based model")
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
      MyUtils.time(getModelP(rank), "(Parallel) item-based model")
    else
      MyUtils.time(getModel(rank), "(Sequential) item-based model")
  }

  def writeModelOnFile(model: IterableOnce[(String, (String, Double))], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    // we are printing to a file; therefore, parallelization would not improve performances
    model.iterator foreach (el => {
      out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
    })
    out.close()
  }

  /*
  private def getModel(rank: (String, String) => Double): IterableOnce[(String, (String, Double))] = {
    // Main parallelization happens here
    execution match {
      case 0 =>
        for {
          u <- users
          s <- songs
          //if !usersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
        } yield u -> (s, rank(u, s))
      case 1 =>
        for {
          u <- users.iterator.toSeq.par
          s <- songs.iterator.toSeq.par
          //if !usersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
        } yield u -> (s, rank(u, s))
      case 2 =>
        // TODO
        System.exit(1)
        for {
          u <- users
          s <- songs
          //if !usersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
        } yield u -> (s, rank (u, s) )
      case _ =>
        System.exit(- 1)
        for {
          u <- users
          s <- songs
          //if !usersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
        } yield u -> (s, rank (u, s) )
    }
  }

  def getUserBasedModel(): Unit = {
    // it calculates the cosine similarity between two users
    def cosineSimilarity(user1: String, user2: String): Double = {
      // Here, parallelization does not improve performances (TODO: check)
      val numerator = songs.iterator.toSeq.map(song =>
        // if both users listened to song return 1, else 0
        if (usersToSongsMap(user1).contains(song) && usersToSongsMap(user2).contains(song) ) 1 else 0
      ).fold(0) { (acc, tup) => acc + tup }
      // usersToSongMap(user).length represents how many songs the user listened to
      val denominator = sqrt(usersToSongsMap(user1).length) * sqrt(usersToSongsMap(user2).length)
      if (denominator != 0) numerator / denominator else 0.0
    }

    def rank(user: String, song: String): Double = {
      // Here, parallelization does not improve performances (TODO: check)
      for {
        u2 <- users
        if u2 != user && usersToSongsMap(u2).contains(song)
      } yield {
        cosineSimilarity(user, u2)
      }
    } sum

    // Calculate model
    val userBasedModel = execution match {
      case 0 =>
        MyUtils.time(getModel(rank), "(Sequential) user-based model")
      case 1 => MyUtils.time(getModel(rank), "(Parallel) user-based model")
      case 2 =>
        // TODO
        System.exit(1)
        MyUtils.time(getModel(rank), "(Distributed) user-based model")
      case _ =>
        System.exit(-1)
        MyUtils.time(getModel(rank), "")
    }

    // Save model to file
    execution match {
      case 0 => writeModelOnFile(userBasedModel, "models/userBasedModel.txt")
      case 1 => writeModelOnFile(userBasedModel, "models/userBasedModelP.txt")
      case 2 =>
        // TODO
        System.exit(1)
        writeModelOnFile(userBasedModel, "models/userBasedModelD.txt")
      case _ =>
        System.exit(-1)
    }
  }

  def getItemBasedModel() = {
    // it calculates the cosine similarity between two songs
    def cosineSimilarity(song1: String, song2: String): Double = {
      // Here, parallelization does not improve performances (TODO: check)
      val numerator = users.iterator.map(user => (
        // if the user listened to both songs return 1, else 0
        if (songsToUsersMap(song1).contains(user) && songsToUsersMap(song2).contains(user)) 1 else 0
        ) ).fold(0) { (acc, tup) => acc + tup }
      // pre-calculate denominator to catch if it is equal to 0
      val denominator = sqrt(songsToUsersMap(song1).length) * sqrt(songsToUsersMap(song2).length)
      if (denominator != 0) numerator / denominator else 0
    }

    def rank(user: String, song: String): Double = {
      // Here, parallelization does not improve performances (TODO: check)
      for {
        s2 <- songs
        if s2 != song
        if usersToSongsMap(user).contains(s2)
      } yield { cosineSimilarity(song, s2) }
    } sum

    val itemBasedModel = execution match {
      case 0 =>
        MyUtils.time(getModel(rank), "(Sequential) item-based model")
      case 1 => MyUtils.time(getModel(rank), "(Parallel) item-based model")
      case 2 =>
        // TODO
        System.exit(1)
        MyUtils.time(getModel(rank), "(Distributed) item-based model")
      case _ =>
        System.exit(-1)
        MyUtils.time(getModel(rank), "")
    }

    // Save model to file
    execution match {
      case 0 => writeModelOnFile(itemBasedModel, "models/itemBasedModel.txt")
      case 1 => writeModelOnFile(itemBasedModel, "models/itemBasedModelP.txt")
      case 2 =>
        // TODO
        System.exit(1)
        writeModelOnFile(itemBasedModel, "models/itemBasedModelD.txt")
      case _ =>
        System.exit(-1)
    }
  }

  private def importModel(pathToModel: String): List[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)
    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.IeeeOrdering.reverse)
    val model = modelFile.getLines().toList map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }

  def getLinearCombinationModel(alpha: Double): Unit = {
    def linearCombination(): IterableOnce[(String, (String, Double))] = {
      execution match {
        case 0 =>
          val ubm = importModel("models/userBasedModel.txt")
          val ibm = importModel("models/itemBasedModel.txt")
          // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
          ubm.zip(ibm).map({
            // for each pair
            case ((user1, song1, rank1), (user2, song2, rank2)) =>
              if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
              // return (user, song, ranks linear combination)
              (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
          })
        case 1 =>
          val ubm = importModel("models/userBasedModelP.txt")
          val ibm = importModel("models/itemBasedModelP.txt")
          // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
          ubm.zip(ibm).par.map({
            // for each pair
            case ((user1, song1, rank1), (user2, song2, rank2)) =>
              if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
              // return (user, song, ranks linear combination)
              (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
          })
        case 2 =>
          // TODO
          System.exit(1)
          val ubm = importModel("models/userBasedModel.txt")
          val ibm = importModel("models/itemBasedModel.txt")
          ubm.zip(ibm).map({
            // for each pair
            case ((user1, song1, rank1), (user2, song2, rank2)) =>
              if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
              // return (user, song, ranks linear combination)
              (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
          })
        case _ =>
          System.exit(-1)
          val ubm = importModel("models/userBasedModel.txt")
          val ibm = importModel("models/itemBasedModel.txt")
          ubm.zip(ibm).map({
            // for each pair
            case ((user1, song1, rank1), (user2, song2, rank2)) =>
              if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
              // return (user, song, ranks linear combination)
              (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
          })
      }
    }

    val linearCombinationModel = MyUtils.time(linearCombination(), "linear combination model")

    // Save model to file
    execution match {
      case 0 => writeModelOnFile(linearCombinationModel, "models/linearCombinationModel.txt")
      case 1 => writeModelOnFile(linearCombinationModel, "models/linearCombinationModelP.txt")
      case 2 =>
        // TODO
        System.exit(1)
        writeModelOnFile(linearCombinationModel, "models/linearCombinationModelD.txt")
      case _ =>
        System.exit(-1)
    }
  }

  def getAggregationModel(itemBasedPercentage: Double = 0.5): Unit ={

    // Exit if percentage is not in the range 0 <= p <= 1
    if(itemBasedPercentage < 0 || itemBasedPercentage > 1) {
      System.err.println("Percentage must be between 0 and 1\n");
      System.exit(-1);
    }

    def aggregation(): IterableOnce[(String, (String, Double))] = {
      execution match {
        case 0 =>
          val ubm = importModel("models/userBasedModel.txt")
          val ibm = importModel("models/itemBasedModel.txt")
          val length = ubm.length
          val itemBasedThreshold = (itemBasedPercentage*length).toInt
          // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
          // TODO: find a better solution than zipWithIndex (may be a slow solution)
          ubm.zip(ibm).zipWithIndex.map({
            // for each pair
            case (couple, index) => couple match {
              case ((user1, song1, rank1), (user2, song2, rank2)) =>
                if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
                // based on the percentage, take the rank of one model
                if(index < itemBasedThreshold) (user1, (song1, rank2))
                else (user1, (song1, rank1))
            }
          })
        case 1 =>
          val ubm = importModel("models/userBasedModelP.txt")
          val ibm = importModel("models/itemBasedModelP.txt")
          val length = ubm.length
          val itemBasedThreshold = (itemBasedPercentage*length).toInt
          // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
          // TODO: find a better solution than zipWithIndex (may be a slow solution)
          ubm.zip(ibm).zipWithIndex.par.map({
            // for each pair
            case (couple, index) => couple match {
              case ((user1, song1, rank1), (user2, song2, rank2)) =>
                if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
                // based on the percentage, take the rank of one model
                if(index < itemBasedThreshold) (user1, (song1, rank2))
                else (user1, (song1, rank1));
            }
          })
        case 2 =>
          // TODO
          System.exit(1)
          val ubm = importModel("models/userBasedModelD.txt")
          val ibm = importModel("models/itemBasedModelD.txt")
          val length = ubm.length
          val itemBasedThreshold = (itemBasedPercentage*length).toInt
          // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
          // TODO: find a better solution than zipWithIndex (may be a slow solution)
          ubm.zip(ibm).zipWithIndex.map({
            // for each pair
            case (couple, index) => couple match {
              case ((user1, song1, rank1), (user2, song2, rank2)) =>
                if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
                // based on the percentage, take the rank of one model
                if(index < itemBasedThreshold) (user1, (song1, rank2))
                else (user1, (song1, rank1));
            }
          })
        case _ =>
          System.exit(-1)
          val ubm = importModel("models/userBasedModel.txt")
          val ibm = importModel("models/itemBasedModel.txt")
          val length = ubm.length
          val itemBasedThreshold = (itemBasedPercentage*length).toInt
          // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
          ubm.zip(ibm).zipWithIndex.map({
            // for each pair
            case (couple, index) => couple match {
              case ((user1, song1, rank1), (user2, song2, rank2)) =>
                if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
                // based on the percentage, take the rank of one model
                if(index < itemBasedThreshold) (user1, (song1, rank2))
                else (user1, (song1, rank1));
            }
          })
      }
    }

    val aggregationModel = MyUtils.time(aggregation(), "aggregation model")

    // Save model to file
    execution match {
      case 0 => writeModelOnFile(aggregationModel, "models/aggregationModel.txt")
      case 1 => writeModelOnFile(aggregationModel, "models/aggregationModelP.txt")
      case 2 =>
        // TODO
        System.exit(1)
        writeModelOnFile(aggregationModel, "models/aggregationModelD.txt")
      case _ =>
        System.exit(-1)
    }
  }

  def getStochasticCombinationModel(itemBasedProbability: Double = 0.5): Unit = {

    // Exit if percentage is not in the range 0 <= p <= 1
    if(itemBasedProbability < 0 || itemBasedProbability > 1) {
      System.err.println("Probability must be between 0 and 1\n");
      System.exit(-1);
    }

    def stochasticCombination(): IterableOnce[(String, (String, Double))] = {
      execution match {
        case 0 =>
          val ubm = importModel("models/userBasedModel.txt")
          val ibm = importModel("models/itemBasedModel.txt")
          val random = new Random
          // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
          ubm.zip(ibm).map({
            // for each pair
            case ((user1, song1, rank1), (user2, song2, rank2)) =>
              if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
              // based on the probability, take the rank of one model
              if(random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
              else (user1, (song1, rank1))
          })
        case 1 =>
          val ubm = importModel("models/userBasedModelP.txt")
          val ibm = importModel("models/itemBasedModelP.txt")
          val random = new Random
          // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
          ubm.zip(ibm).map({
            // for each pair
            case ((user1, song1, rank1), (user2, song2, rank2)) =>
              if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
              // based on the probability, take the rank of one model
              if(random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
              else (user1, (song1, rank1))
          })
        case 2 =>
          // TODO
          System.exit(1)
          val ubm = importModel("models/userBasedModelD.txt")
          val ibm = importModel("models/itemBasedModelD.txt")
          val random = new Random
          // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
          ubm.zip(ibm).map({
            // for each pair
            case ((user1, song1, rank1), (user2, song2, rank2)) =>
              if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
              // based on the probability, take the rank of one model
              if(random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
              else (user1, (song1, rank1))
          })
        case _ =>
          System.exit(-1)
          val ubm = importModel("models/userBasedModel.txt")
          val ibm = importModel("models/itemBasedModel.txt")
          val random = new Random
          // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
          ubm.zip(ibm).map({
            // for each pair
            case ((user1, song1, rank1), (user2, song2, rank2)) =>
              if ((user1 != user2) || (song1 != song2)) System.exit(2)  // Catch error during zip
              // based on the probability, take the rank of one model
              if(random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
              else (user1, (song1, rank1))
          })
      }
    }

    val stochasticCombinationModel = MyUtils.time(stochasticCombination(), "stochastic combination model")

    // Save model to file
    execution match {
      case 0 => writeModelOnFile(stochasticCombinationModel, "models/stochasticCombinationModel.txt")
      case 1 => writeModelOnFile(stochasticCombinationModel, "models/stochasticCombinationModelP.txt")
      case 2 =>
        // TODO
        System.exit(1)
        writeModelOnFile(stochasticCombinationModel, "models/stochasticCombinationModelD.txt")
      case _ =>
        System.exit(-1)
    }
  }

  private def writeModelOnFile(model: IterableOnce[(String, (String, Double))], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    // we are printing to a file; therefore, parallelization would not improve performances
    model.iterator foreach (el => {
      out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
    })
    out.close()
  }
  */
}