package esw.ocs.api.codecs

import csw.command.client.cbor.MessageCodecs
import csw.command.client.messages.sequencer.SequencerMsg
import csw.location.api.codec.DoneCodec
import esw.ocs.api.models.StepStatus.Finished.{Failure, Success}
import esw.ocs.api.models.StepStatus._
import esw.ocs.api.models._
import esw.ocs.api.models.messages.EditorError._
import esw.ocs.api.models.messages.SequenceComponentMsg._
import esw.ocs.api.models.messages.SequenceComponentResponse.{GetStatusResponse, LoadScriptResponse}
import esw.ocs.api.models.messages.SequencerMessages._
import esw.ocs.api.models.messages._
import io.bullet.borer.Codec
import io.bullet.borer.derivation.ArrayBasedCodecs.deriveUnaryCodec
import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec

trait OcsCodecs extends MessageCodecs with DoneCodec {
  //EswSequencerMsg Codecs
  implicit lazy val loadSequenceCodec: Codec[LoadSequence]   = deriveCodec[LoadSequence]
  implicit lazy val startSequenceCodec: Codec[StartSequence] = deriveCodec[StartSequence]
  implicit lazy val loadAndStartSequenceInternalCodec: Codec[LoadAndStartSequenceInternal] =
    deriveCodec[LoadAndStartSequenceInternal]

  implicit lazy val pullNextCodec: Codec[PullNext]                     = deriveUnaryCodec[PullNext]
  implicit lazy val maybeNextCodec: Codec[MaybeNext]                   = deriveUnaryCodec[MaybeNext]
  implicit lazy val readyToExecuteNextCodec: Codec[ReadyToExecuteNext] = deriveUnaryCodec[ReadyToExecuteNext]
  implicit lazy val updateCodec: Codec[Update]                         = deriveCodec[Update]
  implicit lazy val goIdleCodec: Codec[GoIdle]                         = deriveUnaryCodec[GoIdle]

  implicit lazy val getSequenceCodec: Codec[GetSequence]                 = deriveUnaryCodec[GetSequence]
  implicit lazy val getPreviousSequenceCodec: Codec[GetPreviousSequence] = deriveUnaryCodec[GetPreviousSequence]
  implicit lazy val addCodec: Codec[Add]                                 = deriveCodec[Add]
  implicit lazy val prependCodec: Codec[Prepend]                         = deriveCodec[Prepend]
  implicit lazy val replaceCodec: Codec[Replace]                         = deriveCodec[Replace]
  implicit lazy val insertAfterCodec: Codec[InsertAfter]                 = deriveCodec[InsertAfter]
  implicit lazy val deleteCodec: Codec[Delete]                           = deriveCodec[Delete]
  implicit lazy val resetCodec: Codec[Reset]                             = deriveUnaryCodec[Reset]
  implicit lazy val addBreakpointCodec: Codec[AddBreakpoint]             = deriveCodec[AddBreakpoint]
  implicit lazy val removeBreakpointCodec: Codec[RemoveBreakpoint]       = deriveCodec[RemoveBreakpoint]
  implicit lazy val pauseCodec: Codec[Pause]                             = deriveUnaryCodec[Pause]
  implicit lazy val resumeCodec: Codec[Resume]                           = deriveUnaryCodec[Resume]
  implicit lazy val goOnlineCodec: Codec[GoOnline]                       = deriveUnaryCodec[GoOnline]
  implicit lazy val goOnlineSuccessCodec: Codec[GoOnlineSuccess]         = deriveUnaryCodec[GoOnlineSuccess]
  implicit lazy val goOnlineFailedCodec: Codec[GoOnlineFailed]           = deriveUnaryCodec[GoOnlineFailed]
  implicit lazy val goOfflineCodec: Codec[GoOffline]                     = deriveUnaryCodec[GoOffline]
  implicit lazy val goneOfflineCodec: Codec[GoneOffline]                 = deriveUnaryCodec[GoneOffline]
  implicit lazy val shutdownSequencerCodec: Codec[Shutdown]              = deriveUnaryCodec[Shutdown]
  implicit lazy val shutdownCompleteCodec: Codec[ShutdownComplete]       = deriveUnaryCodec[ShutdownComplete]

  implicit lazy val abortSequenceCodec: Codec[AbortSequence]                 = deriveUnaryCodec[AbortSequence]
  implicit lazy val abortSequenceCompleteCodec: Codec[AbortSequenceComplete] = deriveUnaryCodec[AbortSequenceComplete]

  implicit lazy val eswSequencerMessageCodec: Codec[EswSequencerMessage] = deriveCodec[EswSequencerMessage]
  implicit lazy val editorActionCodec: Codec[EditorAction]               = deriveCodec[EditorAction]

  //StepList Codecs
  implicit lazy val stepCodec: Codec[Step]                    = deriveCodec[Step]
  implicit lazy val stepListCodec: Codec[StepList]            = deriveCodec[StepList]
  implicit lazy val successStatusCodec: Codec[Success]        = deriveCodec[Success]
  implicit lazy val failureStatusCodec: Codec[Failure]        = deriveCodec[Failure]
  implicit lazy val pendingStatusCodec: Codec[Pending.type]   = singletonCodec(Pending)
  implicit lazy val inflightStatusCodec: Codec[InFlight.type] = singletonCodec(InFlight)
  implicit lazy val stepStatusCodec: Codec[StepStatus]        = deriveCodec[StepStatus]

  //StepListResponse Codecs
  implicit lazy val responseCodec: Codec[EswSequencerResponse]                       = deriveCodec[EswSequencerResponse]
  implicit lazy val stepListResponseCodec: Codec[StepListResult]                     = deriveCodec[StepListResult]
  implicit lazy val pullNextResultCodec: Codec[PullNextResult]                       = deriveCodec[PullNextResult]
  implicit lazy val maybeNextResultCodec: Codec[MaybeNextResult]                     = deriveCodec[MaybeNextResult]
  implicit lazy val sequenceResultCodec: Codec[SequenceResult]                       = deriveCodec[SequenceResult]
  implicit lazy val okCodec: Codec[Ok.type]                                          = singletonCodec(Ok)
  implicit lazy val duplicateIdsFoundCodec: Codec[DuplicateIdsFound]                 = deriveUnaryCodec[DuplicateIdsFound]
  implicit lazy val sequencerBehaviorStateCodec: Codec[SequencerState[SequencerMsg]] = enumCodec[SequencerState[SequencerMsg]]
  implicit lazy val unhandledCodec: Codec[Unhandled]                                 = deriveCodec[Unhandled]
  implicit lazy val idDoesNotExistCodec: Codec[IdDoesNotExist]                       = deriveCodec[IdDoesNotExist]
  implicit lazy val inFlightOrFinishedStepErrorCodec: Codec[CannotOperateOnAnInFlightOrFinishedStep.type] =
    singletonCodec(CannotOperateOnAnInFlightOrFinishedStep)
  implicit lazy val goOnlineHookFailedCodec: Codec[GoOnlineHookFailed] = deriveUnaryCodec[GoOnlineHookFailed]

  //SequenceComponentCodecs
  implicit lazy val loadScriptCodec: Codec[LoadScript]                     = deriveCodec[LoadScript]
  implicit lazy val loadScriptErrorCodec: Codec[RegistrationError]         = deriveCodec[RegistrationError]
  implicit lazy val getStatusCodec: Codec[GetStatus]                       = deriveCodec[GetStatus]
  implicit lazy val unloadScriptCodec: Codec[UnloadScript]                 = deriveCodec[UnloadScript]
  implicit lazy val stopCodec: Codec[Stop.type]                            = singletonCodec(Stop)
  implicit lazy val sequenceComponentMsgCodec: Codec[SequenceComponentMsg] = deriveCodec[SequenceComponentMsg]

  //SequenceComponentResponse Codecs
  implicit lazy val loadScriptResponseCodec: Codec[LoadScriptResponse]               = deriveUnaryCodec[LoadScriptResponse]
  implicit lazy val getStatusResponseCodec: Codec[GetStatusResponse]                 = deriveUnaryCodec[GetStatusResponse]
  implicit lazy val sequenceComponentResponseCodec: Codec[SequenceComponentResponse] = deriveCodec[SequenceComponentResponse]
}
