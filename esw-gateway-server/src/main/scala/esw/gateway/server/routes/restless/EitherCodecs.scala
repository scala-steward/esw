package esw.gateway.server.routes.restless

import com.github.ghik.silencer.silent
import csw.params.core.formats.CodecHelpers
import esw.gateway.server.routes.restless.Result.{Error, Success}
import io.bullet.borer.{Codec, Decoder, Encoder}

sealed trait Result[E, S] {
  def toEither: Either[E, S] = this match {
    case Success(value) => Right(value)
    case Error(value)   => Left(value)
  }
}

object Result {
  case class Success[E, S](value: S) extends Result[E, S]
  case class Error[E, S](value: E)   extends Result[E, S]

  def fromEither[E, S](either: Either[E, S]): Result[E, S] = either match {
    case Left(value)  => Error(value)
    case Right(value) => Success(value)
  }
}

trait EitherCodecs {
  import io.bullet.borer.derivation.MapBasedCodecs._
  import io.bullet.borer.derivation.ArrayBasedCodecs.deriveCodecForUnaryCaseClass

  implicit def resultCodec[E: Encoder: Decoder, S: Encoder: Decoder]: Codec[Result[E, S]] = {
    @silent implicit lazy val errorCodec: Codec[Error[E, S]]     = deriveCodecForUnaryCaseClass[Error[E, S]]
    @silent implicit lazy val successCodec: Codec[Success[E, S]] = deriveCodecForUnaryCaseClass[Success[E, S]]
    deriveCodec[Result[E, S]]
  }

  implicit def eitherCodec[E: Encoder: Decoder, S: Encoder: Decoder]: Codec[Either[E, S]] = {
    CodecHelpers.bimap[Result[E, S], Either[E, S]](_.toEither, Result.fromEither)
  }

  implicit def eitherEnc[E: Encoder: Decoder, S: Encoder: Decoder]: Encoder[Either[E, S]] = eitherCodec[E, S].encoder
  implicit def eitherDec[E: Encoder: Decoder, S: Encoder: Decoder]: Decoder[Either[E, S]] = eitherCodec[E, S].decoder
}
