package binstaller.config

private[config] final case class DecodeResult[+A](value: A, errors: Vector[ValidationError]):
  def map[B](f: A => B): DecodeResult[B] = DecodeResult(f(value), errors)

private[config] object DecodeResult:
  def valid[A](value: A): DecodeResult[A] = DecodeResult(value, Vector.empty)

  def invalid[A](value: A, path: String, message: String): DecodeResult[A] =
    DecodeResult(value, Vector(ValidationError(path, message)))
