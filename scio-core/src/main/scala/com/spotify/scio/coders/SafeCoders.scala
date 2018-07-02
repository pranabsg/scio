// /*
//  * Copyright 2016 Spotify AB.
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *     http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing,
//  * software distributed under the License is distributed on an
//  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//  * KIND, either express or implied.  See the License for the
//  * specific language governing permissions and limitations
//  * under the License.
//  */

package com.spotify.scio.coders

import java.io.{InputStream, OutputStream}
import org.apache.beam.sdk.coders.{ Coder => BCoder, _}
// import org.apache.beam.sdk.util.CoderUtils
// import com.twitter.bijection._
// import java.io.{ ObjectInputStream, ObjectOutputStream }
// import scala.reflect.ClassTag
// import scala.collection.{ mutable => m }

// import com.spotify.scio.coders.Coder.from

// trait WrappedCoder[T] extends BCoder[T] with Serializable {
//   def underlying: BCoder[T]
//   def encode(value: T, os: OutputStream): Unit =
//     underlying.encode(value, os)
//   def decode(is: InputStream): T =
//     underlying.decode(is)
// }

// final class AvroRawCoder[T](@transient var schema: org.apache.avro.Schema) extends Coder[T] {

//   // makes the schema scerializable
//   val schemaString = schema.toString

//   @transient lazy val _schema = new org.apache.avro.Schema.Parser().parse(schemaString)

//   @transient lazy val model = new org.apache.avro.specific.SpecificData()
//   @transient lazy val encoder = new org.apache.avro.message.RawMessageEncoder[T](model, _schema)
//   @transient lazy val decoder = new org.apache.avro.message.RawMessageDecoder[T](model, _schema)

//   def encode(value: T, os: OutputStream): Unit =
//     encoder.encode(value, os)

//   def decode(is: InputStream): T =
//     decoder.decode(is)
// }

// //
// // Derive Coder from Serializable values
// //
// private[scio] class SerializableCoder[T] extends Coder[T] {
//   def decode(in: InputStream): T =
//     new ObjectInputStream(in).readObject().asInstanceOf[T]
//   def encode(ts: T, out: OutputStream): Unit =
//     new ObjectOutputStream(out).writeObject(ts)
// }

// trait FromSerializable {
//   // XXX: probably a bad idea...
//   // implicit def serializableCoder[T <: Serializable](implicit l: shapeless.LowPriority): Coder[T] =
//   //   new SerializableCoder[T]

//   private[scio] implicit def function1Coder[I, O]: Coder[I => O] =
//     new SerializableCoder[I => O]
// }

// //
// // Derive Coder from twitter Bijection
// //
// class CollectionfromBijection[A, B](
//   implicit b: Bijection[Seq[A], B], c: Coder[Seq[A]]) extends Coder[B] {
//     def encode(ts: B, out: OutputStream): Unit =
//       c.encode(b.invert(ts), out)
//     def decode(in: InputStream): B =
//       b(c.decode(in))
// }

// class MapfromBijection[K, A, B](
//   implicit b: Bijection[Map[K, A], B], c: Coder[Map[K, A]]) extends Coder[B] {
//     def encode(ts: B, out: OutputStream): Unit =
//       c.encode(b.invert(ts), out)
//     def decode(in: InputStream): B =
//       b(c.decode(in))
// }

// trait FromBijection {

//   implicit def collectionfromBijection[A, B](
//     implicit b: Bijection[Seq[A], B], //TODO: should I use ImplicitBijection ?
//              c: Coder[Seq[A]]): Coder[B] =
//     new CollectionfromBijection[A, B]

//   implicit def mapfromBijection[K, A, B](
//     implicit b: Bijection[Map[K, A], B], //TODO: should I use ImplicitBijection ?
//              c: Coder[Map[K, A]]): Coder[B] =
//     new MapfromBijection[K, A, B]
// }

final case class Param[T, PT](label: String, tc: Coder[PT], dereference: T => PT) {
  type PType = PT
}

//
// Derive Coder using Magnolia
//
private object Help {
  @inline def onErrorMsg[T](msg: String)(f: => T) =
    try { f }
    catch { case e: Exception =>
      throw new RuntimeException(msg, e)
    }
}

/**
* Create a serializable coder by trashing all references to magnolia classes
*/

private object Derived {

  def addErrInfo[A](label: String, c: Coder[A]): Coder[A] =
    Coder.transform(c) { bc =>
      Coder.beam(new CustomCoder[A] {
        def encode(value: A, os: OutputStream): Unit =
          Help.onErrorMsg(s"Exception while trying to `encode` field ${label} in ${value}") {
            bc.encode(value, os)
          }
        def decode(is: InputStream): A =
          Help.onErrorMsg(s"Exception while trying to `decode` field ${label}") {
            bc.decode(is)
          }
      })
    }

  def add[A](a: Coder[Seq[A]], b: Coder[A]): Coder[Seq[A]] =
    Coder.transform(a) { ca =>
      Coder.transform(b) { cb =>
        Coder.beam(new CustomCoder[Seq[A]]{
          def encode(value: Seq[A], os: OutputStream): Unit = {
            ca.encode(value.init, os)
            cb.encode(value.last, os)
          }
          def decode(is: InputStream): Seq[A] = {
            ca.decode(is) :+ cb.decode(is)
          }
        })
      }
    }

  def toSeq[A](a: Coder[A]): Coder[Seq[A]] =
    Coder.xmap(a)(a => Seq(a), _.head)

  def combineCoder[T](ps: Seq[Param[T, _]], rawConstruct: Seq[Any] => T): Coder[T] = {
    val cs =
      ps.map { case Param(label, tc, deref) =>
        addErrInfo[Any](label, tc.asInstanceOf[Coder[Any]])
      }
    val coderValues =
      cs.tail.foldLeft(toSeq(cs.head)) { case (cs, c) =>
        add(cs, c)
      }
    Coder.xmap(coderValues)(rawConstruct, v => ps.map(_.dereference(v)))
  }

}

// private final class CombineCoder[T](ps: Seq[Param[T, _]], rawConstruct: Seq[Any] => T) extends BCoder[T]{
//   def encode(value: T, os: OutputStream): Unit = {
//     ps.foreach { case Param(label, tc, deref) =>
//       Help.onErrorMsg(s"Exception while trying to `encode` field ${label} in ${value}") {
//         tc.encode(deref(value), os)
//       }
//     }
//   }

//   def decode(is: InputStream): T =
//     rawConstruct {
//       val arr = scala.collection.mutable.ArrayBuffer[Any]()
//       val size = ps.length
//       var i = 0
//       while(i < size) {
//         val Param(label, typeclass, _) = ps(i)
//         Help.onErrorMsg(s"Exception while trying to `decode` field ${label}") {
//           arr += typeclass.decode(is)
//           i = i + 1
//         }
//       }
//       arr
//     }
// }

// private final class DispatchCoder[T](sealedTrait: magnolia.SealedTrait[Coder, T]) extends BCoder[T]{
//   val idx: Map[magnolia.TypeName, Int] =
//     sealedTrait.subtypes.map(_.typeName).zipWithIndex.toMap
//   val idc = VarIntCoder.of()

//   def encode(value: T, os: OutputStream): Unit =
//     sealedTrait.dispatch(value) { subtype =>
//       Help.onErrorMsg(s"Exception while trying to dispatch call to `encode` for class ${subtype.typeName.full}") {
//         idc.encode(idx(subtype.typeName), os)
//         subtype.typeclass.encode(subtype.cast(value), os)
//       }
//     }

//   def decode(is: InputStream): T = {
//     val id = idc.decode(is)
//     val subtype = sealedTrait.subtypes(id)
//     Help.onErrorMsg(s"Exception while trying to dispatch call to `decode` for class ${subtype.typeName.full}"){
//       subtype.typeclass.decode(is)
//     }
//   }
// }

trait LowPriorityCoderDerivation {
  import language.experimental.macros, magnolia._
  import com.spotify.scio.avro.types.CoderUtils

  type Typeclass[T] = Coder[T]

  def combine[T](ctx: CaseClass[Coder, T]): Coder[T] = {
    val ps =
      ctx.parameters.map { p =>
        Param[T, p.PType](p.label, p.typeclass, p.dereference _)
      }
    Derived.combineCoder(ps, ctx.rawConstruct _)
  }

  def dispatch[T](sealedTrait: SealedTrait[Coder, T]): Coder[T] = ???
//     new DispatchCoder[T](sealedTrait)

  def gen[T]: Coder[T] = macro CoderUtils.wrappedCoder[T]
}

// trait auto {
//   import language.experimental.macros
//   import com.spotify.scio.avro.types.CoderUtils
//   // TODO: can we provide magnolia nice error message when gen is used implicitly ?
//   implicit def autoCoder[T]: Coder[T] = macro CoderUtils.wrappedCoder[T]
// }

// object auto extends auto

// //
// // Avro Coders
// //
// private[scio] object fallback {
//   def apply[T: scala.reflect.ClassTag](sc: com.spotify.scio.ScioContext): Coder[T] =
//     from(com.spotify.scio.Implicits.RichCoderRegistry(sc.pipeline.getCoderRegistry)
//       .getScalaCoder[T](sc.options))
// }

import org.apache.avro.generic.GenericRecord
import org.apache.avro.Schema

// private class SlowGenericRecordCoder extends Coder[GenericRecord]{

//   var coder: Coder[GenericRecord] = _
//   // TODO: can we find something more efficient than String ?
//   val sc = StringUtf8Coder.of()

//   def encode(value: GenericRecord, os: OutputStream): Unit = {
//     val schema = value.getSchema
//     if(coder == null) {
//       coder = from(AvroCoder.of(schema))
//     }
//     sc.encode(schema.toString, os)
//     coder.encode(value, os)
//   }

//   def decode(is: InputStream): GenericRecord = {
//     val schemaStr = sc.decode(is)
//     if(coder == null) {
//       val schema = new Schema.Parser().parse(schemaStr)
//       coder = from(AvroCoder.of(schema))
//     }
//     coder.decode(is)
//   }
// }

trait AvroCoders {
//   self: BaseCoders =>
//   import language.experimental.macros
//   // TODO: Use a coder that does not serialize the schema
  def genericRecordCoder(schema: Schema): Coder[GenericRecord] = ???
//     new AvroRawCoder(schema)

//   // XXX: similar to GenericAvroSerializer
//   def slowGenericRecordCoder: Coder[GenericRecord] =
//     new SlowGenericRecordCoder

//   import org.apache.avro.specific.SpecificRecordBase
//   implicit def genAvro[T <: SpecificRecordBase]: Coder[T] =
//     macro com.spotify.scio.avro.types.CoderUtils.staticInvokeCoder[T]
}

// //
// // Protobuf Coders
// //
// trait ProtobufCoders {
//   implicit def bytestringCoder: Coder[com.google.protobuf.ByteString] = ???
//   implicit def timestampCoder: Coder[com.google.protobuf.Timestamp] = ???
//   implicit def protoGeneratedMessageCoder[T <: com.google.protobuf.GeneratedMessageV3]: Coder[T] = ???
// }

// //
// // Java Coders
// //
// trait JavaCoders {
//   self: BaseCoders with FromBijection =>

//   implicit def uriCoder: Coder[java.net.URI] = ???
//   implicit def pathCoder: Coder[java.nio.file.Path] = ???
//   import java.lang.{Iterable => jIterable}
//   implicit def jIterableCoder[T](implicit c: Coder[T]): Coder[jIterable[T]] = ???
//   // Could be derived from Bijection but since it's a very common one let's just support it.
//   implicit def jlistCoder[T](implicit c: Coder[T]): Coder[java.util.List[T]] =
//     collectionfromBijection[T, java.util.List[T]]

//   private def fromScalaCoder[J <: java.lang.Number, S <: AnyVal](coder: Coder[S]): Coder[J] =
//     coder.asInstanceOf[Coder[J]]

//   implicit val jIntegerCoder: Coder[java.lang.Integer] = fromScalaCoder(Coder.intCoder)
//   implicit val jLongCoder: Coder[java.lang.Long] = fromScalaCoder(Coder.longCoder)
//   implicit val jDoubleCoder: Coder[java.lang.Double] = fromScalaCoder(Coder.doubleCoder)
//   // TODO: Byte, Float, Short

//   implicit def mutationCaseCoder: Coder[com.google.bigtable.v2.Mutation.MutationCase] = ???
//   implicit def mutationCoder: Coder[com.google.bigtable.v2.Mutation] = ???

//   implicit def boundedWindowCoder: Coder[org.apache.beam.sdk.transforms.windowing.BoundedWindow] = ???
//   implicit def intervalWindowCoder: Coder[org.apache.beam.sdk.transforms.windowing.IntervalWindow] = ???
//   implicit def paneinfoCoder: Coder[org.apache.beam.sdk.transforms.windowing.PaneInfo] = ???
//   implicit def instantCoder: Coder[org.joda.time.Instant] = ???
//   implicit def tablerowCoder: Coder[com.google.api.services.bigquery.model.TableRow] =
//     from(org.apache.beam.sdk.io.gcp.bigquery.TableRowJsonCoder.of())
//   implicit def messageCoder: Coder[org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage] = ???
//   implicit def entityCoder: Coder[com.google.datastore.v1.Entity] = ???
//   implicit def statcounterCoder: Coder[com.spotify.scio.util.StatCounter] = ???
// }

// trait AlgebirdCoders {
//   self: LowPriorityCoderDerivation with BaseCoders =>

//   import com.twitter.algebird._
//   implicit def cmsHashCoder[K: Coder : CMSHasher] = gen[CMSHash[K]]
//   implicit def cmsCoder[K: Coder](implicit hcoder: Coder[CMSHash[K]]) = gen[CMS[K]]
//   implicit def bfCoder[K](implicit c: Coder[K]): Coder[com.twitter.algebird.BF[K]] = ???
//   implicit def topKCoder[K](implicit c: Coder[K]): Coder[com.twitter.algebird.TopK[K]] = gen[com.twitter.algebird.TopK[K]]
// }

private object UnitCoder extends CustomCoder[Unit]{
  def encode(value: Unit, os: OutputStream): Unit = ()
  def decode(is: InputStream): Unit = ()
}

private object NothingCoder extends CustomCoder[Nothing] {
  def encode(value: Nothing, os: OutputStream): Unit = ()
  def decode(is: InputStream): Nothing = ??? // can't possibly happen
}

// private class OptionCoder[T: Coder] extends Coder[Option[T]] {
//   val bcoder = BooleanCoder.of().asInstanceOf[BCoder[Boolean]]
//   def encode(value: Option[T], os: OutputStream): Unit = {
//     bcoder.encode(value.isDefined, os)
//     value.foreach { Coder[T].encode(_, os) }
//   }

//   def decode(is: InputStream): Option[T] =
//     Option(bcoder.decode(is)).collect {
//       case true => Coder[T].decode(is)
//     }
// }

// private class SeqCoder[T: Coder] extends Coder[Seq[T]] {
//   val lc = VarIntCoder.of()
//   def decode(in: InputStream): Seq[T] = {
//     val l = lc.decode(in)
//     (1 to l).map { _ =>
//       Coder[T].decode(in)
//     }
//   }

//   def encode(ts: Seq[T], out: OutputStream): Unit = {
//     lc.encode(ts.length, out)
//     ts.foreach { v => Coder[T].encode(v, out) }
//   }
// }

// private class ListCoder[T: Coder] extends Coder[List[T]] {
//   val seqCoder = new SeqCoder[T]
//   def encode(value: List[T], os: OutputStream): Unit =
//     seqCoder.encode(value.toSeq, os)
//   def decode(is: InputStream): List[T] =
//     seqCoder.decode(is).toList
// }

// private class IterableCoder[T: Coder] extends Coder[Iterable[T]] {
//   val seqCoder = new SeqCoder[T]
//   def encode(value: Iterable[T], os: OutputStream): Unit =
//     seqCoder.encode(value.toSeq, os)
//   def decode(is: InputStream): Iterable[T] =
//     seqCoder.decode(is)
// }

// private class VectorCoder[T: Coder] extends Coder[Vector[T]] {
//   val seqCoder = new SeqCoder[T]
//   def encode(value: Vector[T], os: OutputStream): Unit =
//     seqCoder.encode(value.toSeq, os)
//   def decode(is: InputStream): Vector[T] =
//     seqCoder.decode(is).toVector
// }

// private class ArrayCoder[T: Coder : ClassTag] extends Coder[Array[T]] {
//   val seqCoder = new SeqCoder[T]
//   def encode(value: Array[T], os: OutputStream): Unit =
//     seqCoder.encode(value.toSeq, os)
//   def decode(is: InputStream): Array[T] =
//     seqCoder.decode(is).toArray
// }

// private class ArrayBufferCoder[T: Coder] extends Coder[m.ArrayBuffer[T]] {
//   val seqCoder = new SeqCoder[T]
//   def encode(value: m.ArrayBuffer[T], os: OutputStream): Unit =
//     seqCoder.encode(value.toSeq, os)
//   def decode(is: InputStream): m.ArrayBuffer[T] =
//     m.ArrayBuffer(seqCoder.decode(is):_*)
// }

// private class MapCoder[K: Coder, V: Coder] extends Coder[Map[K, V]] {
//   val lc = VarIntCoder.of()
//   def decode(in: InputStream): Map[K, V] = {
//     val l = lc.decode(in)
//     (1 to l).map { _ =>
//       val k = Coder[K].decode(in)
//       val v = Coder[V].decode(in)
//       (k, v)
//     }.toMap
//   }

//   def encode(ts: Map[K, V], out: OutputStream): Unit = {
//     lc.encode(ts.size, out)
//     ts.foreach { case (k, v) =>
//       Coder[K].encode(k, out)
//       Coder[V].encode(v, out)
//     }
//   }
// }

// private class MutableMapCoder[K: Coder, V: Coder] extends Coder[m.Map[K, V]] {
//   val lc = VarIntCoder.of()
//   def decode(in: InputStream): m.Map[K, V] = {
//     val l = lc.decode(in)
//     m.Map((1 to l).map { _ =>
//       val k = Coder[K].decode(in)
//       val v = Coder[V].decode(in)
//       (k, v)
//     }:_*)
//   }

//   def encode(ts: m.Map[K, V], out: OutputStream): Unit = {
//     lc.encode(ts.size, out)
//     ts.foreach { case (k, v) =>
//       Coder[K].encode(k, out)
//       Coder[V].encode(v, out)
//     }
//   }
// }

trait AtomCoders {
  import Coder.beam
  implicit def byteCoder: Coder[Byte] = beam(ByteCoder.of().asInstanceOf[BCoder[Byte]])
  implicit def byteArrayCoder: Coder[Array[Byte]] = beam(ByteArrayCoder.of())
  implicit def bytebufferCoder: Coder[java.nio.ByteBuffer] = beam(???)
  implicit def stringCoder: Coder[String] = beam(StringUtf8Coder.of())
  implicit def intCoder: Coder[Int] = beam(VarIntCoder.of().asInstanceOf[BCoder[Int]])
  implicit def doubleCoder: Coder[Double] = beam(DoubleCoder.of().asInstanceOf[BCoder[Double]])
  implicit def floatCoder: Coder[Float] = beam(FloatCoder.of().asInstanceOf[BCoder[Float]])
  implicit def unitCoder: Coder[Unit] = beam(UnitCoder)
  implicit def nothingCoder: Coder[Nothing] = beam[Nothing](NothingCoder)
  implicit def booleanCoder: Coder[Boolean] = beam(BooleanCoder.of().asInstanceOf[BCoder[Boolean]])
  implicit def longCoder: Coder[Long] = beam(BigEndianLongCoder.of().asInstanceOf[BCoder[Long]])
  implicit def bigdecimalCoder: Coder[BigDecimal] = ???
}

// trait BaseCoders {
//   // TODO: support all primitive types
//   // BigDecimalCoder
//   // BigIntegerCoder
//   // BitSetCoder
//   // BooleanCoder
//   // ByteStringCoder

//   // DurationCoder
//   // InstantCoder

//   // TableRowJsonCoder

//   implicit def traversableCoder[T](implicit c: Coder[T]): Coder[TraversableOnce[T]] = ???
//   implicit def optionCoder[T](implicit c: Coder[T]): Coder[Option[T]] = new OptionCoder[T]

//   // TODO: proper chunking implementation
//   implicit def iterableCoder[T](implicit c: Coder[T]): Coder[Iterable[T]] = new IterableCoder[T]

//   implicit def throwableCoder: Coder[Throwable] = ???

//   // specialized coder. Since `::` is a case class, Magnolia would derive an incorrect one...
//   implicit def listCoder[T: Coder]: Coder[List[T]] = new ListCoder[T]
//   implicit def vectorCoder[T: Coder]: Coder[Vector[T]] = new VectorCoder[T]
//   implicit def seqCoder[T: Coder]: Coder[Seq[T]] = new SeqCoder[T]
//   implicit def arraybufferCoder[T: Coder]: Coder[m.ArrayBuffer[T]] = new ArrayBufferCoder[T]
//   implicit def bufferCoder[T: Coder]: Coder[scala.collection.mutable.Buffer[T]] = ???
//   implicit def arrayCoder[T: Coder : ClassTag]: Coder[Array[T]] = new ArrayCoder[T]
//   implicit def mutableMapCoder[K: Coder, V: Coder]: Coder[m.Map[K, V]] = new MutableMapCoder[K, V]
//   implicit def mapCoder[K: Coder, V: Coder]: Coder[Map[K, V]] = new MapCoder[K, V]
//   implicit def sortedSetCoder[T: Coder]: Coder[scala.collection.SortedSet[T]] = ???

//   implicit def enumerationCoder[E <: Enumeration]: Coder[E#Value] = ???
// }

trait Implicits
  extends LowPriorityCoderDerivation
//   with FromSerializable
//   with FromBijection
//   with BaseCoders
  with AvroCoders
//   with ProtobufCoders
//   with JavaCoders
//   with AlgebirdCoders
//   with Serializable

object Implicits extends Implicits