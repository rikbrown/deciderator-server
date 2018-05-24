package codes.rik.deciderator.types

import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
interface UncertaintyRequest

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
interface UncertaintyMessage
abstract class UncertaintyErrorMessage(val error: String): UncertaintyMessage

data class SetUsernameRequest(val username: String): UncertaintyRequest
data class UsernameSetMessage(val name: String): UncertaintyMessage

object CreateUncertaintyRequest: UncertaintyRequest
data class UncertaintyCreatedMessage(val uncertaintyId: UncertaintyId): UncertaintyMessage

data class JoinUncertaintyRequest(val uncertaintyId: UncertaintyId): UncertaintyRequest
data class UncertaintyJoinedMessage(val uncertaintyId: UncertaintyId): UncertaintyMessage
data class UncertaintyNotFoundMessage(val uncertaintyId: UncertaintyId): UncertaintyErrorMessage("Uncertainty not found: ${uncertaintyId.id}")

data class MakeDecisionRequest(val uncertaintyId: UncertaintyId): UncertaintyRequest
data class DecidingMessage(val uncertaintyId: UncertaintyId): UncertaintyMessage
data class DecisionMessage(val uncertaintyId: UncertaintyId, val decision: String): UncertaintyMessage

data class HelloMessage(
        val sessionId: SessionId,
        val onlineSessionIds: Set<SessionId>,
        val message: String): UncertaintyMessage

data class UncertaintyActiveUsersMessage(
        val uncertaintyId: UncertaintyId,
        val users: Set<UncertaintyJoinedUser>): UncertaintyMessage