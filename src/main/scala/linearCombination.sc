import java.io.{BufferedWriter, File, FileWriter}
import scala.language.postfixOps
import scala.io.{BufferedSource, Source}

def userModelBasedFile: BufferedSource = Source.fromResource("models/userBasedModel.txt")
def itemModelBasedFile: BufferedSource = Source.fromResource("models/itemBasedModel.txt")

val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.IeeeOrdering.reverse)
val userBasedModel = userModelBasedFile.getLines().toList map (line => line split "\t" match{case Array(users,songs,ranks)=>(users,songs,ranks.toDouble)}) sorted ordering
val itemBasedModel = itemModelBasedFile.getLines().toList map (line => line split "\t" match{case Array(users,songs,ranks)=>(users,songs,ranks.toDouble)}) sorted ordering

def linearCombination(userBased:List[(String,String,Double)],itemBased:List[(String,String,Double)], alpha:Double): List[(String, String, Double)] = {
  // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
  val zippedList = userBased zip itemBased
  // for each pair
  zippedList map {
    case ((user, song, rank1), (_, _, rank2)) =>
      // return (user, song, linear combination)
      (user, song, rank1 * alpha + rank2 * (1 - alpha))
  }
}

def time[R](block: => R): R = {
  // get start time
  val t0 = System.nanoTime()
  // execute code
  val result = block
  // get end time
  val t1 = System.nanoTime()
  // print elapsed time
  println("Elapsed time: " + (t1 - t0)/1000000 + "ms")
  // return the result
  result
}

val linearCombined = time(linearCombination(userBasedModel,itemBasedModel,0.5)) sorted(Ordering.by[(String, String, Double), Double](_._3) reverse) groupBy(_._1)

val file = new File(getClass.getClassLoader.getResource("models/linearCombination.txt").getPath)
val bw = new BufferedWriter(new FileWriter(file))
linearCombined map(el => {
  el._2 map (row => {
    bw.write(row._1 + "\t" + row._2 + "\t" + row._3 + "\n")
  })
})
bw.close()