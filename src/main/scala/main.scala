import music_recommandation.MusicRecommender

import scala.collection.immutable._
import scala.collection.parallel.CollectionConverters._
import scala.io._
import scala.language._

object main {
  def main(args: Array[String]): Unit = {
    // 0: sequential, 1: parallel, 2: distributed
    val execution = 1
    // verbosity of the output
    val verbose = true
    // name of the file containing the considered dataset
    val fileName: String = "train_triplets_5k.txt"

    // import dataset
    def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource(fileName).getPath)

    /*
     * We could have used a single map, but having both can be useful for some tricks later on
     * Between space and temporal efficiency, we chose the latter
     */
    def songsByUser(): (List[String], List[String], Map[String, List[String]], Map[String, List[String]]) = {
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
      (usersInFile.toList , songsInFile.toList , songsUsersMap.toMap, usersSongsMap.toMap)
    }

    // get data from file
    val (users, songs, usersToSongsMap, songsToUsersMap) = songsByUser()

    // print number of total users and songs in the file
    if (verbose) println(s"File \'$fileName\' contains ${users.length} users and ${songs.length} songs")

    val trainUsers = users.grouped(users.length-10).toList.head
    val testUsers = users.grouped(users.length-10).toList(1)

    val trainSet = trainUsers.map(u => (u,usersToSongsMap(u))).toMap
    val testLabels = testUsers.map(u => (u, usersToSongsMap(u))).toMap

    def calTestSet(testLabels: Map[String, List[String]]) = {
      for {u <- testLabels}
        yield (u._1, u._2.grouped((u._2.length / 2) + 1).toList.head)
    }

    val testSet = calTestSet(testLabels)

    /*
    for {u <- testLabels}
      println(u._2.length)

    println("##########################")

    for {u <- testSet}
      println(u._2.length)
     */

    print(testLabels.head)

    //val input = trainSet ++ testSet

    // instantiate musicRecommender
    //val musicRecommender: MusicRecommender = new MusicRecommender(users, songs, usersToSongsMap, songsToUsersMap, execution)
    //val musicRecommender: MusicRecommender = new MusicRecommender(users, songs, input, songsToUsersMap, execution)

    // calculating user-based model
    //musicRecommender.getUserBasedModel()
    //musicRecommender.getItemBasedModel()
    //musicRecommender.getLinearCombinationModel(0.5)
    //musicRecommender.getAggregationModel(0.5)
    //musicRecommender.getStochasticCombinationModel(0.5)
  }
}
