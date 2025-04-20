/*
!!!NOTE!!!
The code below was taken fully from https://github.com/gnieh/tekstlib/blob/master/src/main/scala/gnieh/string/Rope.scala
and modified in a way that it is now compatible with scala 3



* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
//package gnieh
//package string
package rope

/** Rope data structure as a binary tree of character arrays.
 *  First described in `Ropes: an Alternative to Strings`
 *  by Hans-J. Boehm, Russ Atkinson and Michael Plass.
 *
 *  @author Lucas Satabin
 */
sealed abstract class Rope {

  def size: Int

  def depth: Int

  def charAt(idx: Int): Char

  def splitAt(idx: Int): (Rope, Rope)

  def insertAt(idx: Int, that: Rope): Rope = {
    val (r1, r2) = splitAt(idx)
    r1 + that + r2
  }

  def delete(start: Int, length: Int): Rope = {
    val (r1, r2) = splitAt(start - 1)
    val (_, r3) = r2.splitAt(length)
    r1 + r3
  }

  def +(that: Rope): Rope

  def +(s: String): Rope =
    this + RopeLeaf(s.toArray)

  def +(a: Array[Char]): Rope =
    this + RopeLeaf(a)

  def +(c: Char): Rope =
    this + RopeLeaf(Array(c))

  def substring(start: Int, length: Int): Rope = {
    val (_, r1) = splitAt(start - 1)
    val (r2, _) = r1.splitAt(length)
    r2
  }

  def substring(start: Int): Rope = {
    val (_, r1) = splitAt(start - 1)
    r1
  }

  def toString: String

  def map(f: Char => Char): Rope

  def flatMap(f: Char => Rope): Rope

  def filter(f: Char => Boolean): Rope

  def withFilter(f: Char => Boolean): Rope =
    filter(f)

  def foreach(f: Char => Unit): Unit

  def foldLeft[Acc](zero: Acc)(f: (Acc, Char) => Acc): Acc

  def foldRight[Acc](zero: Acc)(f: (Char, Acc) => Acc): Acc

  def isBalanced: Boolean

  def toList: List[Array[Char]]

}

object Rope {

  /** Creates a rope from a string */
  def apply(s: String): Rope =
    Rope(s.toArray)

  /** Creates a rope from a character array. */
  def apply(a: Array[Char]): Rope =
    if (a == null || a.isEmpty) {
      RopeEmpty
    } else if (a.length > 2048) {
      val (a1, a2) = a.splitAt(a.length / 2)
      Rope(a1) + Rope(a2)
    } else {
      RopeLeaf(a)
    }

  /** Creates a rope from a character. */
  def apply(c: Char): Rope =
    RopeLeaf(Array(c))

  def balance(r: Rope): Rope =
    if (r.isBalanced)
      r
    else
      fromList(r.toList)

  private def fromList(l: List[Array[Char]]): Rope =
    l match {
      case List(s1, s2) =>
        RopeConcat(RopeLeaf(s1), RopeLeaf(s2))
      case List(s) =>
        RopeLeaf(s)
      case Nil =>
        RopeEmpty
      case _ =>
        val (half1, half2) = l.splitAt(l.size / 2)
        RopeConcat(fromList(half1), fromList(half2))
    }

}

private case object RopeEmpty extends Rope {

  def size: Int =
    0

  def depth: Int =
    0

  def charAt(idx: Int): Char =
    throw new StringIndexOutOfBoundsException(f"String index out of range: $idx")

  def splitAt(idx: Int): (Rope, Rope) =
    (RopeEmpty, RopeEmpty)

  def +(that: Rope): Rope =
    that

  override def toString: String =
    ""
  def map(f: Char => Char): Rope =
    this

  def flatMap(f: Char => Rope): Rope =
    this

  def filter(f: Char => Boolean): Rope =
    this

  def foreach(f: Char => Unit): Unit =
    ()

  def foldLeft[Acc](zero: Acc)(f: (Acc, Char) => Acc): Acc =
    zero

  def foldRight[Acc](zero: Acc)(f: (Char, Acc) => Acc): Acc =
    zero

  val isBalanced: Boolean =
    true

  def toList: List[Array[Char]] =
    Nil

}

private final case class RopeConcat(left: Rope, right: Rope) extends Rope {

  val size: Int =
    left.size + right.size

  val depth: Int =
    1 + math.max(left.depth, right.depth)

  def charAt(idx: Int): Char =
    if (idx < 0)
      throw new StringIndexOutOfBoundsException(f"String index out of range: $idx")
    else if (idx >= size)
      right.charAt(idx - size)
    else
      left.charAt(idx)

  def splitAt(idx: Int): (Rope, Rope) =
    if (idx < 0) {
      (RopeEmpty, this)
    } else if (idx >= size) {
      (this, RopeEmpty)
    } else if (idx >= left.size) {
      val (r1, r2) = right.splitAt(idx - left.size)
      (left + r1, r2)
    } else {
      val (r1, r2) = left.splitAt(idx)
      (r1, r2 + right)
    }

  def +(that: Rope): Rope =
    (right, that) match {
      case (_, RopeEmpty) =>
        this
      case (RopeLeaf(rightValue), RopeLeaf(thatValue)) if rightValue.length + thatValue.length <= 2048 =>
        Rope.balance(left + Rope(rightValue ++ thatValue))
      case _ =>
        Rope.balance(RopeConcat(this, that))
    }

  override def toString: String =
    left.toString + right.toString

  def map(f: Char => Char): Rope =
    left.map(f) + right.map(f)

  def flatMap(f: Char => Rope): Rope =
    left.flatMap(f) + right.flatMap(f)

  def filter(f: Char => Boolean): Rope =
    left.filter(f) + right.filter(f)

  def foreach(f: Char => Unit): Unit = {
    left.foreach(f)
    right.foreach(f)
  }

  def foldLeft[Acc](zero: Acc)(f: (Acc, Char) => Acc): Acc =
    right.foldLeft(left.foldLeft(zero)(f))(f)

  def foldRight[Acc](zero: Acc)(f: (Char, Acc) => Acc): Acc =
    left.foldRight(right.foldRight(zero)(f))(f)

  val isBalanced: Boolean =
    math.abs(left.depth - right.depth) < 4

  def toList: List[Array[Char]] =
    left.toList ++ right.toList

}

private final case class RopeLeaf(value: Array[Char]) extends Rope {

  val size: Int =
    value.length

  val depth =
    0

  def charAt(idx: Int): Char =
    value(idx)

  def splitAt(idx: Int): (Rope, Rope) = {
    val (s1, s2) = value.splitAt(idx)
    (Rope(s1), Rope(s2))
  }

  def +(that: Rope): Rope =
    that match {
      case RopeEmpty =>
        this
      case RopeLeaf(thatValue) if this.value.length + thatValue.length <= 2048 =>
        Rope(this.value ++ thatValue)
      case _ =>
        Rope.balance(RopeConcat(this, that))
    }

  override def toString: String =
    value.mkString

  def map(f: Char => Char): Rope =
    RopeLeaf(value.map(f))

  def flatMap(f: Char => Rope): Rope =
    value.foldLeft(Rope("")) { (acc, c) => acc + f(c) }

  def filter(f: Char => Boolean): Rope =
    Rope(value.filter(f))

  def foreach(f: Char => Unit): Unit =
    value.foreach(f)

  def foldLeft[Acc](zero: Acc)(f: (Acc, Char) => Acc): Acc =
    value.foldLeft(zero)(f)

  def foldRight[Acc](zero: Acc)(f: (Char, Acc) => Acc): Acc =
    value.foldRight(zero)(f)

  val isBalanced: Boolean =
    true

  def toList: List[Array[Char]] =
    List(value)

}