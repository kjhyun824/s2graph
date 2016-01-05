package com.kakao.s2graph.core.storage.redis

import com.kakao.s2graph.core.{GraphExceptions, SnapshotEdge}
import com.kakao.s2graph.core.mysqls.LabelIndex
import com.kakao.s2graph.core.storage.{SKeyValue, StorageSerializable}
import com.kakao.s2graph.core.types.{VertexId, SourceAndTargetVertexIdPair, HBaseType}
import org.apache.hadoop.hbase.util.Bytes

/**
  * Created by june.kay on 2015. 12. 31..
  */
class RedisSnapshotEdgeSerializable(snapshotEdge: SnapshotEdge) extends StorageSerializable[SnapshotEdge] {
  import StorageSerializable._

  val label = snapshotEdge.label

  override def toKeyValues: Seq[SKeyValue] = {
    label.schemaVersion match {
      case HBaseType.VERSION3 => toKeyValuesInnerV3
      case _ => throw new GraphExceptions.NotSupportedSchemaVersion(">> Redis storage engine can support only v3.")
    }
  }

  def statusCodeWithOp(statusCode: Byte, op: Byte): Array[Byte] = {
    val byte = (((statusCode << 4) | op).toByte)
    Array.fill(1)(byte.toByte)
  }

  def valueBytes() = Bytes.add(statusCodeWithOp(snapshotEdge.statusCode, snapshotEdge.op),
    propsToKeyValuesWithTs(snapshotEdge.props.toList))

  private def toKeyValuesInnerV3: Seq[SKeyValue]  = {
    val srcIdAndTgtIdBytes = SourceAndTargetVertexIdPair(snapshotEdge.srcVertex.innerId, snapshotEdge.tgtVertex.innerId).bytes
    val labelWithDirBytes = snapshotEdge.labelWithDir.bytes
    val labelIndexSeqWithIsInvertedBytes = labelOrderSeqWithIsInverted(LabelIndex.DefaultSeq, isInverted = true)

    val row = Bytes.add(
      srcIdAndTgtIdBytes.takeRight(srcIdAndTgtIdBytes.length - 2),
      labelWithDirBytes,
      labelIndexSeqWithIsInvertedBytes
    )

    val value = snapshotEdge.pendingEdgeOpt match {
      case None => valueBytes()
      case Some(pendingEdge) =>
        val opBytes = statusCodeWithOp(pendingEdge.statusCode, pendingEdge.op)
        val versionBytes = Array.empty[Byte]
        val propsBytes = propsToKeyValuesWithTs(pendingEdge.propsWithTs.toSeq)
        val lockBytes = Bytes.toBytes(pendingEdge.lockTs.get)
        Bytes.add(Bytes.add(valueBytes(), opBytes, versionBytes), Bytes.add(propsBytes, lockBytes))
    }

    val kv = SKeyValue(Array.empty[Byte], row, Array.empty[Byte], Array.empty[Byte], value, snapshotEdge.version)
    Seq(kv)
  }
}