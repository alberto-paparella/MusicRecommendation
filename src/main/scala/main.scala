import music_recommandation.MusicRecommender
import my_utils.MyUtils.time

import scala.collection.immutable._
import scala.collection.parallel.CollectionConverters._
import scala.io._
import scala.language._

object main {
  def main(args: Array[String]): Unit = {
    // 0: sequential, 1: parallel, 2: distributed
    val execution = 0
    // verbosity of the output
    val verbose = true
    // name of the file containing the considered dataset
    val fileName: String = "train_triplets_50k.txt"
    // number of (maximum) users and songs to consider
    val nUsedUsers = 100
    val nUsedSongs = 100

    // import dataset
    def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource(fileName).getPath)

    def songsByUser(): (List[String], List[String], Map[String, List[String]]) = {
      // map user1->[{songs listened by user1}], ..., userN->[{songs listened by userN}]
      val songsUsersMap = collection.mutable.Map[String, List[String]]()
      // all users in file
      val usersInFile = collection.mutable.Set[String]()
      // all songs in file
      val songsInFile = collection.mutable.Set[String]()
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
        }
      }
      (usersInFile.toList , songsInFile.toList , songsUsersMap.toMap)
    }

    // get data from file
    val (users, songs, usersToSongsMap) = songsByUser()

    // print number of total users and songs in the file
    if (verbose) println(s"File \'$fileName\' contains ${users.length} users and ${songs.length} songs")

    // TEST-ONLY: using a subset of users and songs
    val (usedUsers: IterableOnce[String], usedSongs: IterableOnce[String]) = {
      if (execution == 0) (users slice(0, nUsedUsers), songs slice(0, nUsedSongs))
      else if (execution == 1) (users.par slice(0, nUsedUsers), songs.par slice(0, nUsedSongs))
      else if (execution == 2) {if (verbose) println("\n! Todo !\n"); System.exit(1)}
      else {if (verbose) println("\n! Error !\n"); System.exit(-1)}
    }
    // print number of users and songs that are actually being used
    if (verbose) println(s"Using ${usedUsers.iterator.length} users and ${usedSongs.iterator.length} songs")

    // instantiate musicRecommender
    val musicRecommender: MusicRecommender = new MusicRecommender(usedUsers, usedSongs, usersToSongsMap, execution)

    // calculating user-based model
    musicRecommender.getUserBasedModel()
    musicRecommender.getItemBasedModel()
    musicRecommender.getLinearCombinationModel(0.5)
    musicRecommender.getAggregationModel(0.5)
    musicRecommender.getStochasticCombinationModel(0.5)
  }
}
