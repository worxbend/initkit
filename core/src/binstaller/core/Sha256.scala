package binstaller.core

import java.security.MessageDigest

private[core] object Sha256:

  def digest(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString
