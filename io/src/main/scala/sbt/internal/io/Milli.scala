/*
 * sbt IO
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt.internal.io

import java.io.{ File, FileNotFoundException, IOException }
import java.nio.{ ByteBuffer, ByteOrder }
import java.nio.file.Files
import java.util.Date

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase.{ FILETIME, INVALID_HANDLE_VALUE }
import com.sun.jna.platform.win32.WinError.{
  ERROR_ACCESS_DENIED,
  ERROR_FILE_NOT_FOUND,
  ERROR_INVALID_NAME,
  ERROR_PATH_NOT_FOUND
}
import com.sun.jna.platform.win32.WinNT._
import com.sun.jna.{ FromNativeContext, Library, NativeMapped, Platform, Native => JNANative }
import sbt.internal.io.MacJNA._
import sbt.io.JavaMilli

import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.reflect.{ ClassTag, classTag }
import scala.util.control.NonFatal

private abstract class Stat[Time_T](size: Int) extends NativeMapped {
  val buffer = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder())

  def fromNative(nativeValue: Object, context: FromNativeContext) =
    throw new UnsupportedOperationException("Not supported.")

  def toNative(): Object = buffer
  def nativeType(): Class[ByteBuffer] = classOf[ByteBuffer]

  def getModifiedTimeNative: (Time_T, Time_T) // (sec, nsec)
}

private abstract class StatLong(size: Int, mtimeOffset: Int, mtimensecOffset: Int)
    extends Stat[Long](size) {
  def getModifiedTimeNative = (buffer.getLong(mtimeOffset), buffer.getLong(mtimensecOffset))
}
private abstract class StatInt(size: Int, mtimeOffset: Int, mtimensecOffset: Int)
    extends Stat[Int](size) {
  def getModifiedTimeNative = (buffer.getInt(mtimeOffset), buffer.getInt(mtimensecOffset))
}

private[sbt] abstract class Milli {
  def getModifiedTime(filePath: String): Long
  def setModifiedTime(filePath: String, mtime: Long): Unit
  def copyModifiedTime(fromFilePath: String, toFilePath: String): Unit
}

private abstract class MilliNative[Native] extends Milli {
  def getModifiedTime(filePath: String): Long =
    fromNative(getModifiedTimeNative(filePath))
  def setModifiedTime(filePath: String, mtime: Long): Unit =
    setModifiedTimeNative(filePath, toNative(mtime))

  // If we have more refined native time information, we can use that
  // information when copying the modified time, rather than using milliseconds
  override def copyModifiedTime(fromFilePath: String, toFilePath: String): Unit =
    setModifiedTimeNative(toFilePath, getModifiedTimeNative(fromFilePath))

  protected def getModifiedTimeNative(filePath: String): Native
  protected def setModifiedTimeNative(filePath: String, mtimeNative: Native): Unit
  protected def fromNative(mtimeNative: Native): Long // get milliseconds from native time
  protected def toNative(mtime: Long): Native // convert milliseconds to native time
}

/**
 * FFI for standard C library.
 */
private sealed trait PosixBase extends Library {

  /** http://www.cplusplus.com/reference/cstring/strerror/ */
  def strerror(errnum: Int): String
}

private abstract class MilliPosixBase[Interface <: PosixBase: ClassTag, Native]
    extends MilliNative[Native] {
  private val options = scala.collection.Map[String, Object]().asJava
  private final val ENOENT = 2
  protected val libc: Interface =
    (JNANative.load(
      Platform.C_LIBRARY_NAME,
      classTag[Interface].runtimeClass.asInstanceOf[Class[Interface]],
      options
    )): Interface
  protected def checkedIO[T](filePath: String)(f: => Int) = {
    if (f != 0) {
      val errno = JNANative.getLastError()
      val errStr = libc.strerror(JNANative.getLastError())
      if (errno == ENOENT)
        throw new FileNotFoundException(errStr + ": " + filePath)
      else
        throw new IOException(errStr + ", file: " + filePath)
    }
  }
}

private trait Posix[Time_T] extends PosixBase
private abstract class PosixMilli[Interface <: Posix[Time_T]: ClassTag, Time_T]
    extends MilliPosixBase[Interface, (Time_T, Time_T)] {
  protected def fromLongLong(sec_nsec: (Long, Long)): Long = {
    val (sec, nsec) = sec_nsec
    sec * 1000L + (nsec / 1000000L)
  }
  protected def toLongLong(mtime: Long): (Long, Long) = {
    val sec = mtime / 1000L
    val nsec = (mtime - sec * 1000L) * 1000000L
    (sec, nsec)
  }
}

private abstract class PosixMilliLong[Interface <: Posix[Long]: ClassTag]
    extends PosixMilli[Interface, Long] {
  protected def fromNative(sec_nsec: (Long, Long)) = fromLongLong(sec_nsec)
  protected def toNative(mtime: Long) = toLongLong(mtime)
}
private abstract class PosixMilliInt[Interface <: Posix[Int]: ClassTag]
    extends PosixMilli[Interface, Int] {
  protected def fromNative(sec_nsec: (Int, Int)) = {
    val (sec, nsec) = sec_nsec
    fromLongLong((sec.toLong, nsec.toLong))
  }
  protected def toNative(mtime: Long): (Int, Int) = {
    val (sec, nsec) = toLongLong(mtime)
    (sec.toInt, nsec.toInt)
  }
}

// Cannot use classtag to initialize buffer: JNA needs
// a nullary constructor. So, extend manually below.
//
// Note: technically, tv_nsec is a long, not a time_t.
// But on Linux32 a long is 4 bytes (like time_t), and on
// Linux64 a long is 8 bytes (like time_t), so time_t
// and long have the same size in both cases.
//
private abstract class TimeSpec2[Time_T] extends NativeMapped {
  val buffer: Array[Time_T]
  def fromNative(nativeValue: Object, context: FromNativeContext) =
    throw new UnsupportedOperationException("Not supported.")
  def toNative(): Object = buffer
  def nativeType(): Class[Array[Time_T]] = classOf[Array[Time_T]]
}

private class TimeSpec2Long extends TimeSpec2[Long] {
  val buffer = new Array[Long](4)
  def this(sec: Long, nsec: Long, UTIME_OMIT: Long) = {
    this()
    buffer(0) = 0L
    buffer(1) = UTIME_OMIT
    buffer(2) = sec
    buffer(3) = nsec
  }
}

private class TimeSpec2Int extends TimeSpec2[Int] {
  val buffer = new Array[Int](4)
  def this(sec: Int, nsec: Int, UTIME_OMIT: Int) = {
    this()
    buffer(0) = 0
    buffer(1) = UTIME_OMIT
    buffer(2) = sec
    buffer(3) = nsec
  }
}

private trait Utimensat[Time_T] extends Posix[Time_T] {
  def utimensat(dirfd: Int, filePath: String, times: TimeSpec2[Time_T], flags: Int): Int
}
private trait MilliUtimensat[Time_T] {
  protected def AT_FDCWD: Int
  protected def UTIME_OMIT: Time_T
}
private abstract class PosixMilliLongUtim[Interface <: Utimensat[Long]: ClassTag]
    extends PosixMilliLong[Interface]
    with MilliUtimensat[Long] {
  protected def setModifiedTimeNative(filePath: String, mtimeNative: (Long, Long)): Unit = {
    val (sec, nsec) = mtimeNative
    val times = new TimeSpec2Long(sec, nsec, UTIME_OMIT)
    checkedIO(filePath) { libc.utimensat(AT_FDCWD, filePath, times, 0) }
  }
}
private abstract class PosixMilliIntUtim[Interface <: Utimensat[Int]: ClassTag]
    extends PosixMilliInt[Interface]
    with MilliUtimensat[Int] {
  protected def setModifiedTimeNative(filePath: String, mtimeNative: (Int, Int)): Unit = {
    val (sec, nsec) = mtimeNative
    val times = new TimeSpec2Int(sec, nsec, UTIME_OMIT)
    checkedIO(filePath) { libc.utimensat(AT_FDCWD, filePath, times, 0) }
  }
}

private class Linux64FileStat extends StatLong(144, 88, 96)
private trait Linux64 extends Library with Utimensat[Long] {
  def __xstat64(version: Int, filePath: String, buf: Linux64FileStat): Int
}
private object Linux64Milli extends PosixMilliLongUtim[Linux64] {
  protected final val AT_FDCWD: Int = -100
  protected final val UTIME_OMIT: Long = (1L << 30) - 2
  protected def getModifiedTimeNative(filePath: String) = {
    val stat = new Linux64FileStat
    checkedIO(filePath) { libc.__xstat64(1, filePath, stat) }
    stat.getModifiedTimeNative
  }
}

private class Linux32FileStat extends StatInt(88, 64, 68)
private trait Linux32 extends Library with Utimensat[Int] {
  def __xstat(version: Int, filePath: String, buf: Linux32FileStat): Int
}
private object Linux32Milli extends PosixMilliIntUtim[Linux32] {
  protected final val AT_FDCWD: Int = -100
  protected final val UTIME_OMIT: Int = ((1 << 30) - 2)
  protected def getModifiedTimeNative(filePath: String) = {
    val stat = new Linux32FileStat
    checkedIO(filePath) { libc.__xstat(3, filePath, stat) }
    stat.getModifiedTimeNative
  }
}

private trait Mac extends Library with Posix[Long] {
  def getattrlist(
      path: String,
      attrlist: Attrlist,
      attrBuf: TimeBuf,
      attrBufSize: Int,
      options: Int
  ): Int
  def setattrlist(
      path: String,
      attrlist: Attrlist,
      attrBuf: Timespec,
      attrBufSize: Int,
      options: Int
  ): Int
}
private object MacMilli extends PosixMilliLong[Mac] {
  private val attr = new Attrlist
  attr.bitmapcount = 5 // ATTR_BIT_MAP_COUNT
  attr.commonattr = 0x00000400 // ATTR_CMN_MODTIME

  protected def getModifiedTimeNative(filePath: String) = {
    val buf = new TimeBuf
    checkedIO(filePath) { libc.getattrlist(filePath, attr, buf, 20 /* buf size */, 0) }
    (buf.tv_sec, buf.tv_nsec)
  }

  protected def setModifiedTimeNative(filePath: String, mtimeNative: (Long, Long)): Unit = {
    val buf = new Timespec
    val (sec, nsec) = mtimeNative
    buf.tv_sec = sec
    buf.tv_nsec = nsec
    checkedIO(filePath) { libc.setattrlist(filePath, attr, buf, 16 /* buf size */, 0) }
  }
}

private object WinMilli extends MilliNative[FILETIME] {
  import Kernel32.INSTANCE._

  private def getHandle(lpFileName: String, dwDesiredAccess: Int, dwShareMode: Int): HANDLE = {
    // https://docs.microsoft.com/en-us/windows/win32/fileio/naming-a-file
    // \\?\ prefix is used to overcome the 260 character limitation.
    val fileName = "\\\\?\\" + lpFileName
    val hFile = CreateFile(
      fileName,
      dwDesiredAccess,
      dwShareMode,
      null,
      OPEN_EXISTING,
      FILE_FLAG_BACKUP_SEMANTICS,
      null
    )
    if (hFile == INVALID_HANDLE_VALUE) {
      val err = GetLastError()
      if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND)
        throw new FileNotFoundException("Not found: " + lpFileName)
      else if (err == ERROR_ACCESS_DENIED)
        throw new FileNotFoundException("Access denied: " + lpFileName)
      else if (err == ERROR_INVALID_NAME)
        throw new FileNotFoundException("Invalid path name " + lpFileName)
      else {
        throw new IOException(s"CreateFile() failed with error ${GetLastError()}: $fileName")
      }
    }
    hFile
  }

  protected def getModifiedTimeNative(filePath: String): FILETIME = {
    val hFile = getHandle(
      filePath,
      FILE_READ_ATTRIBUTES,
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE
    )
    val mtime =
      try {
        val modifiedTime = new FILETIME.ByReference()
        if (!GetFileTime(hFile, /*creationTime*/ null, /*accessTime*/ null, modifiedTime))
          throw new IOException(
            "GetFileTime() failed with error " + GetLastError() + " for file " + filePath
          )
        modifiedTime
      } finally {
        if (!CloseHandle(hFile))
          throw new IOException(
            "CloseHandle() after GetFileTime() failed with error " + GetLastError() + " for file " + filePath
          )
      }
    mtime
  }

  protected def setModifiedTimeNative(filePath: String, fileTime: FILETIME): Unit = {
    val hFile = getHandle(
      filePath,
      FILE_WRITE_ATTRIBUTES,
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE
    )
    try {
      if (SetFileTime(hFile, null, null, fileTime) == 0)
        throw new IOException(
          "SetFileTime() failed with error " + GetLastError() + " for file " + filePath
        )
    } finally {
      if (!CloseHandle(hFile))
        throw new IOException(
          "CloseHandle() after SetFileTime() failed with error " + GetLastError() + " for file " + filePath
        )
    }
  }

  protected def fromNative(mtimeNative: FILETIME): Long = mtimeNative.toTime
  protected def toNative(mtime: Long): FILETIME = new FILETIME(new Date(mtime))
}

// No native time information? Copy just the milliseconds
private[sbt] abstract class MilliMilliseconds extends Milli {
  def copyModifiedTime(fromFilePath: String, toFilePath: String): Unit =
    setModifiedTime(toFilePath, getModifiedTime(fromFilePath))
}

object Milli {
  import Platform._

  //
  // If the property "sbt.io.jdktimestamps" is set to anything other than
  // "false", disable native millisecond-accurate modification timestamps.
  // millis were missing until Java 10, see https://bugs.openjdk.java.net/browse/JDK-8177809
  //
  private val jdkTimestamps = {
    System.getProperty("sbt.io.jdktimestamps") match {
      case null =>
        System.getProperty("java.specification.version") match {
          case null => false
          case sv =>
            try {
              sv.split("\\.").last.toInt >= 10
            } catch { case NonFatal(_) => false }
        }
      case p => p.toLowerCase != "false"
    }
  }

  val milli: Milli =
    if (jdkTimestamps || !isIntel)
      JavaMilli
    else
      getOSType match {
        case LINUX   => if (is64Bit) Linux64Milli else Linux32Milli
        case MAC     => MacMilli
        case WINDOWS => WinMilli
        case _       => JavaMilli
      }

  def getModifiedTime(file: File): Long =
    milli.getModifiedTime(file.getPath)
  def setModifiedTime(file: File, mtime: Long): Unit =
    milli.setModifiedTime(file.getPath, mtime)
  def copyModifiedTime(fromFile: File, toFile: File): Unit =
    milli.copyModifiedTime(fromFile.getPath, toFile.getPath)

  // A sanity check should always be performed upon startup, in order to inform the
  // user if their file system may betray them because of lack of sub-second
  // resolution, or if their jdk/system do not offer it.
  // DO NOT use /tmp to perform the check: use the project dir, where the user files are located
  // Returns: None if millisecond timestamps worked, Some("diagnostic message") otherwise.
  def getMilliSupportDiagnostic(projectDir: File): Option[String] =
    if (jdkTimestamps)
      None
    else {
      val file = Files.createTempFile(projectDir.toPath(), "sbt.io.Milli", "test-file").toFile
      try {
        val originalTime = getModifiedTime(file)
        setModifiedTime(file, originalTime - 27)
        val newTime = getModifiedTime(file)
        if (newTime + 27 == originalTime)
          None
        else
          Some {
            (getOSType match {
              case LINUX | MAC | WINDOWS =>
                "Your filesystem does not seem to support sub-second file timestamps "
              case _ =>
                "Your OS, filesystem, or Java VM, do not seem to support sub-second file timestamps"
            }) + (getOSType match {
              case LINUX =>
                "(ext2/ext3, for instance, have a 1 sec resolution)"
              case MAC =>
                "(HFS+ has a 1 sec resolution, but APFS is more precise)"
              case WINDOWS =>
                "(FAT32, for instance, has a 1-2 sec resolution but NTFS is more precise)"
              case _ =>
                ""
            }) + ". That may affect sbt's ability to detect rapid file changes."
          }
      } finally {
        if (!file.delete())
          throw new IOException(
            "Unexpected: could not delete temporary file: " + file.getAbsolutePath
          )
      }
    }
}

/*
object MilliDemo extends App {
  import Milli._
  getMilliSupportDiagnostic(new File(".")) map println
  val file = new File("Milli.scala")
  println(getModifiedTime(file))
  setModifiedTime(file, getModifiedTime(file)+1)
  println(getModifiedTime(file))
}
 */
