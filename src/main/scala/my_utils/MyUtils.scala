package my_utils

object MyUtils {
  def time[R](block: => R, operation: String = "unknown"): R = {
    // get start time
    val t0 = System.nanoTime()
    // execute code
    val result = block
    // get end time
    val t1 = System.nanoTime()
    // print elapsed time
    println(s"Elapsed time for $operation:\t" + (t1 - t0) / 1000000 + "ms (" + (t1 - t0) + "ns)")
    // return the result
    result
  }

  def roundAt(p: Int, n: Double): Double = { val s = math pow (10, p); (math round n * s) / s }
}
