package music_recommandation

import my_utils.MyUtils

import java.io._
import scala.collection.parallel.CollectionConverters._
import scala.io._
import scala.language.postfixOps
import scala.math.sqrt

class MusicRecommender(private val users: IterableOnce[String], private val songs: IterableOnce[String],
                       private val usersToSongsMap: IterableOnce[(String, List[String])], execution: Int = 0) {
  private def getModel(rank: (String, String) => Double): IterableOnce[(String, (String, Double))] = {
    execution match {
      case 0 =>
        for {
          u <- users.iterator.toSeq
          s <- songs.iterator.toSeq
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
      u <- users.iterator.toSeq
      s <- songs.iterator.toSeq
        //if !usersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
      } yield u -> (s, rank (u, s) )
    case _ =>
      System.exit(- 1)
      for {
      u <- users.iterator.toSeq
      s <- songs.iterator.toSeq
        //if !usersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
      } yield u -> (s, rank (u, s) )
    }
  }

  def getUserBasedModel(): Unit = {
    // if both users listened to the same song return 1, else 0
    def numerator(user1: String, user2: String, song: String): Int = {
      execution match {
        case 0 =>
          if (usersToSongsMap.iterator.to(Map)(user1).contains(song) && usersToSongsMap.iterator.to(Map)(user2).contains(song) ) 1 else 0
        case 1 =>
          if (usersToSongsMap.iterator.to(Map).par(user1).contains(song) && usersToSongsMap.iterator.to(Map).par(user2).contains(song)) 1 else 0
        case 2 =>
          // TODO
          System.exit(1)
          if (usersToSongsMap.iterator.to(Map)(user1).contains(song) && usersToSongsMap.iterator.to(Map)(user2).contains(song)) 1 else 0
        case _ =>
          System.exit(- 1)
          if (usersToSongsMap.iterator.to(Map)(user1).contains(song) && usersToSongsMap.iterator.to(Map)(user2).contains(song)) 1 else 0
      }
    }

    // it calculates the cosine similarity between two users
    def cosineSimilarity(user1: String, user2: String): Double = {
      val usersTuples = execution match {
        case 0 => songs.iterator.toSeq.map(
          song => (numerator (user1, user2, song),
          if (usersToSongsMap.iterator.to(Map)(user1).contains(song)) 1 else 0,
          if (usersToSongsMap.iterator.to(Map)(user2).contains(song)) 1 else 0
          )
        )
        case 1 => songs.iterator.toSeq.par.map(
          song => (numerator(user1, user2, song),
            if (usersToSongsMap.iterator.to(Map)(user1).contains(song)) 1 else 0,
            if (usersToSongsMap.iterator.to(Map)(user2).contains(song)) 1 else 0
          )
        )
        case 2 =>
          // TODO
          System.exit(1)
          songs.iterator.toSeq.map(
          song => (numerator(user1, user2, song),
            if (usersToSongsMap.iterator.to(Map)(user1).contains(song)) 1 else 0,
            if (usersToSongsMap.iterator.to(Map)(user2).contains(song)) 1 else 0
          )
        )
        case _ =>
          System.exit(-1)
          songs.iterator.toSeq.map(
          song => (numerator(user1, user2, song),
            if (usersToSongsMap.iterator.to(Map)(user1).contains(song)) 1 else 0,
            if (usersToSongsMap.iterator.to(Map)(user2).contains(song)) 1 else 0
          )
        )
      }

      val u = execution match {
        case 0 => usersTuples.iterator.toSeq.fold((0, 0, 0)) {(acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3)}
        case 1 => usersTuples.iterator.toSeq.par.fold((0, 0, 0)) {(acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3)}
        case 2 =>
          // TODO
          System.exit(1)
          usersTuples.iterator.toSeq.fold((0, 0, 0)) {(acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3)}
        case _ =>
          System.exit(-1)
          usersTuples.iterator.toSeq.fold((0, 0, 0)) {(acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3)}
      }

      val denominator = sqrt(u._2) * sqrt(u._3)
      if (denominator != 0) u._1 / denominator else 0.0
    }

    def rank(user: String, song: String): Double = {
      execution match {
        case 0 =>
          for {
            u2 <- users.iterator.toSeq
            if u2 != user
            if usersToSongsMap.iterator.to(Map)(u2).contains(song)
          } yield {
            cosineSimilarity(user, u2)
          }
        case 1 =>
          for {
            u2 <- users.iterator.toSeq.par
            if u2 != user
            if usersToSongsMap.iterator.to(Map).par(u2).contains(song)
          } yield {
            cosineSimilarity(user, u2)
          }
        case 2 =>
          // TODO
          System.exit(1)
          for {
            u2 <- users.iterator.toSeq
            if u2 != user
            if usersToSongsMap.iterator.to(Map)(u2).contains(song)
          } yield {
            cosineSimilarity(user, u2)
          }
        case _ =>
          System.exit(-1)
          for {
            u2 <- users.iterator.toSeq
            if u2 != user
            if usersToSongsMap.iterator.to(Map)(u2).contains(song)
          } yield {
            cosineSimilarity(user, u2)
          }
      }
    }.iterator.sum

    val userBasedModel = execution match {
      case 0 =>
        MyUtils.time(getModel(rank).iterator.toMap, "(Sequential) user-based model")
      case 1 => MyUtils.time(getModel(rank).iterator.toMap.par, "(Parallel) user-based model")
      case 2 =>
        // TODO
        System.exit(1)
        MyUtils.time(getModel(rank).iterator.toMap.par, "(Distributed) user-based model")
      case _ =>
        System.exit(-1)
        MyUtils.time(getModel(rank).iterator.toMap.par, "(Exiting) user-based model")
    }

    execution match {
      case 0 => MyUtils.time(writeModelOnFile(userBasedModel.iterator.toMap, "models/userBasedModel.txt"), "writing")
      case 1 => MyUtils.time(writeModelOnFile(userBasedModel.iterator.toMap.par, "models/userBasedModelP.txt"), "writing")
      case 2 =>
        // TODO
        System.exit(1)
        MyUtils.time(writeModelOnFile(userBasedModel.iterator.toMap, "models/userBasedModelD.txt"), "writing")
      case _ =>
        System.exit(-1)
        MyUtils.time(writeModelOnFile(userBasedModel.iterator.toMap, ""), "")
    }
  }

  def getItemBasedModelRank() = {
    // if the user listened to both songs return 1, else 0
    def numerator(song1: String, song2: String, user: String): Int =
      if (usersToSongsMap.iterator.to(Map)(user).contains(song1) && usersToSongsMap.iterator.to(Map)(user).contains(song2)) 1 else 0

    // it calculates the cosine similarity between two songs
    def cosineSimilarity(song1: String, song2: String): Double = {
      val usersTuples = users.iterator.map(user => (numerator(song1, song2, user),
        if (usersToSongsMap.iterator.to(Map)(user).contains(song1)) 1 else 0,
        if (usersToSongsMap.iterator.to(Map)(user).contains(song2)) 1 else 0
      ))

      val u = usersTuples.iterator.toSeq.fold((0, 0, 0)) {(acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3)}

      val denominator = sqrt(u._2) * sqrt(u._3)
      if (denominator != 0) u._1 / denominator else 0
    }

    def specificFormula(user: String, song: String): Double = {
      for {
        s2 <- songs //filter (s => s != song) //filter deprecated
        if s2 != song
        if usersToSongsMap.iterator.to(Map)(user).contains(s2)
      } yield { cosineSimilarity(song, s2) }
    } sum

    val itemBasedModel = MyUtils.time(getModel(specificFormula), "item-based model")
    print(itemBasedModel)
    //if(outputFileName != "") time(writeModelOnFile(itemBasedModel, outputFileName), "writing")
  }

  private def importModel(pathToModel: String): List[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)
    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.IeeeOrdering.reverse)
    val model = modelFile.getLines().toList map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }

  def getLinearCombinationModelRank(alpha: Double, parallel: Boolean = false, outputFileName: String = ""): Unit = {
    val ubm = importModel("models/userBasedModel.txt")
    val ibm = importModel("models/itemBasedModel.txt")

    def linearCombination(): Iterator[(String, String, Double)] = {
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      val userItemCouple = if (parallel) ubm.zip(ibm).par else ubm zip ibm
      // for each pair
      userItemCouple.iterator.map({
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if(user1 != user2) println("Users different")
          if(song1 != song2) println("Songs different")
          // return (user, song, linear combination)
          (user1, song1, rank1 * alpha + rank2 * (1 - alpha))
      })
    }

    val linearCombined = MyUtils.time(linearCombination(), "linear combination model")
    //if(outputFileName != "") time(writeModelOnFile(linearCombined, outputFileName), "writing")
  }

   private def writeModelOnFile(model: IterableOnce[(String, (String, Double))], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    execution match {
      case 0 =>
        model.iterator.toMap foreach (el => {
          out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
        })
      case 1 =>
        model.iterator.toMap.par foreach (el => {
          out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
        })
      case 2 =>
        // TODO
        System.exit(1)
        model.iterator.toMap foreach (el => {
          out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
        })
      case _ =>
        System.exit(-1)
        model.iterator.toMap foreach (el => {
          out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
        })
    }
    out.close()
  }
}