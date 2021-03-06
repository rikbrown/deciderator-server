package codes.rik.deciderator

import codes.rik.deciderator.types.CoinFace.HEADS
import codes.rik.deciderator.types.CoinFace.TAILS
import codes.rik.deciderator.types.CoinStyle
import codes.rik.deciderator.types.FlipResult
import codes.rik.deciderator.types.OptionName
import codes.rik.deciderator.types.Round
import codes.rik.deciderator.types.Round.RoundData.HeadToHeadRound
import codes.rik.deciderator.types.Round.RoundData.MeaningfulVoteRound
import codes.rik.deciderator.types.Uncertainty
import codes.rik.deciderator.types.UncertaintyId
import codes.rik.deciderator.types.UncertaintyOption
import codes.rik.deciderator.types.UncertaintyRules
import codes.rik.deciderator.types.Username
import codes.rik.deciderator.types.Winner
import codes.rik.deciderator.types.count
import codes.rik.deciderator.types.currentRules
import codes.rik.deciderator.types.remainingOptions
import codes.rik.deciderator.types.replace
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class UncertaintyManager @Inject constructor() {
  private val uncertainties = Uncertainties(ConcurrentHashMap(PLACEHOLDER_UNCERTAINTIES.associateBy { it.id }))

  fun get(uncertaintyId: UncertaintyId) = uncertainties.getSubject(uncertaintyId)

  /**
   * Create a new uncertainty
   */
  fun create(name: String, options: Set<OptionName>): UncertaintyId {
    if (name.isBlank()) throw IllegalArgumentException("Name required")
    if (options.size < 2) throw IllegalArgumentException(">=2 options required")

    val uncertainty = Uncertainty(
      id = uncertainties.createId(),
      name = name,
      rules = UncertaintyRules(
        bestOf = 5,
        finalTwoHeadToHead = true
      ),
      options = options.map {
        UncertaintyOption(
          name = it,
          coinStyle = CoinStyle("germany"),
        )
      },
      currentRound = Round(
        coinStyle = CoinStyle("germany"),
        data = when (options.size) {
          2 -> HeadToHeadRound(options.elementAt(0), options.elementAt(1))
          else -> MeaningfulVoteRound(options.first())
        }
      )
    )
    uncertainties.insert(uncertainty)
    return uncertainty.id
  }

  /**
   * Update the coin style for an uncertainty
   */
  fun updateCoinStyle(uncertaintyId: UncertaintyId, style: CoinStyle) {
    val uncertainty = uncertainties[uncertaintyId]
    uncertainty.copy(
      // always update in current round
      currentRound = uncertainty.currentRound.copy(coinStyle = style),

      // also update for the option if we're in a meaningful vote
      options = when (val roundData = uncertainty.currentRound.data) {
        is MeaningfulVoteRound -> uncertainty.options.replace(roundData.option) { it.copy(coinStyle = style) }
        is HeadToHeadRound -> uncertainty.options
      }
    ).also { uncertainties.replace(uncertainty, it) }
  }

  /**
   * Add a result to an uncertainty, potentially triggering side effects such as round, loop
   * or uncertainty completion.
   */
  fun addResult(uncertaintyId: UncertaintyId, result: FlipResult) {
    val uncertainty = uncertainties[uncertaintyId]

    // Add the new result
    val results = uncertainty.currentRound.results + result

    // Did any face win this round?
    val roundWinningFace = when {
      results.count(HEADS) > uncertainty.currentRules.bestOf.toDouble() / 2 -> HEADS
      results.count(TAILS) > uncertainty.currentRules.bestOf.toDouble() / 2 -> TAILS
      else -> null
    }

    // Update options
    val options = if (roundWinningFace != null) {
      when (val roundData = uncertainty.currentRound.data) {
        is MeaningfulVoteRound -> {
          when (roundWinningFace) {
            HEADS -> uncertainty.options // not eliminated
            TAILS -> uncertainty.options.replace(roundData.option) { it.copy(eliminated = true) }
          }
        }
        is HeadToHeadRound -> {
          when (roundWinningFace) {
            HEADS -> uncertainty.options.replace(roundData.tailsOption) { it.copy(eliminated = true) }
            TAILS -> uncertainty.options.replace(roundData.headsOption) { it.copy(eliminated = true) }
          }
        }
      }
    } else {
      uncertainty.options
    }

    // Determine if we have an *overall* winner
    val winner = when (val roundData = uncertainty.currentRound.data) {
      is MeaningfulVoteRound -> options.singleOrNull { !it.eliminated } // if only one remaining non-eliminated
      is HeadToHeadRound -> roundWinningFace
        ?.let { if (it == HEADS) roundData.headsOption else roundData.tailsOption }
        ?.let { name -> options.find { it.name == name } }
    }

    uncertainties.replace(uncertainty, uncertainty.copy(
      currentRound = uncertainty.currentRound.copy(results = results, winningFace = roundWinningFace),
      options = options,
      winner = winner?.let {
        Winner(it.name,
          coinStyle = uncertainty.currentRound.coinStyle,
          face = results.last().result
        )
      }
    ))
  }

  fun nextRound(uncertaintyId: UncertaintyId) {
    val uncertainty = uncertainties[uncertaintyId]
    val roundData = uncertainty.currentRound.data
    if (roundData !is MeaningfulVoteRound) throw RuntimeException("Next round not possible for ${roundData::class.simpleName}")

    fun makeUpdatedUncertainty(): Uncertainty {
      var idx = uncertainty.options.indexOfFirst { it.name == roundData.option }
      while (true) {
        idx++

        // End of loop?
        if (idx == uncertainty.options.size) {
          // Do we need to do any new loop special behaviour?
          when (uncertainty.remainingOptions.size) {
            1 -> {
              // winner. metadata should already be set, and this shouldn't really be called. no-op.
              return uncertainty
            }
            2 -> {
              // Update uncertainty enabling a H2H round
              return uncertainty.copy(
                currentRound = Round(
                  coinStyle = uncertainty.remainingOptions[Random.nextInt(1)].coinStyle,
                  data = HeadToHeadRound(
                    headsOption = uncertainty.remainingOptions[0].name,
                    tailsOption = uncertainty.remainingOptions[1].name,
                  )
                )
              )
            }
            0 -> {
              // switch to lightning rules, reset elimination status
              val nxtOption = uncertainty.options.first { !it.eliminated }
              return uncertainty.copy(
                currentRound = Round(
                  coinStyle = nxtOption.coinStyle,
                  data = MeaningfulVoteRound(
                    option = nxtOption.name,
                    customRules = uncertainty.rules.copy(bestOf = 1),
                  )
                ),
                options = uncertainty.options.map { it.copy(eliminated = it.startedLoopEliminated) }
              )
            }
          }

          // Otherwise just loop around
          idx = 0
        }

        val nxtOption = uncertainty.options[idx]
        if (!nxtOption.eliminated) {
          return uncertainty.copy(
            currentRound = Round(
              coinStyle = nxtOption.coinStyle,
              data = MeaningfulVoteRound(option = nxtOption.name)
            ),
            options = uncertainty.options.map { it.copy(startedLoopEliminated = it.eliminated) }
          )
        }
      }
    }

    uncertainties.replace(uncertainty, makeUpdatedUncertainty())
  }
}

data class UncertaintyNotFoundException(val id: UncertaintyId) : RuntimeException("Uncertainty not found: $id")

private data class Uncertainties(private val uncertainties: ConcurrentMap<UncertaintyId, Uncertainty>) {
  private val uncertaintySubject: ConcurrentMap<UncertaintyId, BehaviorSubject<Uncertainty>> = ConcurrentHashMap()

  operator fun get(id: UncertaintyId) = uncertainties[id] ?: throw UncertaintyNotFoundException(id)
  fun getSubject(id: UncertaintyId): BehaviorSubject<Uncertainty> = uncertaintySubject.computeIfAbsent(id) { BehaviorSubject.createDefault(get(id)) }

  fun insert(uncertainty: Uncertainty) {
    uncertainties.putIfAbsent(uncertainty.id, uncertainty)
  }

  fun replace(old: Uncertainty, new: Uncertainty) {
    if (old.id != new.id) throw IllegalArgumentException("Cannot replace uncertainty ${old.id} with ${new.id}")

    if (uncertainties.replace(old.id, old, new)) {
      getSubject(old.id).onNext(new)
    }
  }

  fun createId(): UncertaintyId {
    do {
      val id = UncertaintyId.create()
      if (!uncertainties.containsKey(id)) {
        return id
      }
    } while (true)
  }
}

private val PLACEHOLDER_UNCERTAINTIES = listOf(
  Uncertainty(
    id = UncertaintyId("foo"),
    name = "[TEST] What game should we play next?",
    rules = UncertaintyRules(bestOf = 5, finalTwoHeadToHead = true),
    currentRound = Round(
      coinStyle = CoinStyle("eu_germany"),
      data = MeaningfulVoteRound(OptionName("Civ VI")),
      results = listOf(
        FlipResult(
          result = HEADS,
          coinStyle = CoinStyle("germany"),
          flippedBy = Username("Rik"),
          waitTime = Duration.ofMillis(1234),
          flipTime = Duration.ofMillis(4567)
        ),
        FlipResult(
          result = HEADS,
          coinStyle = CoinStyle("germany"),
          flippedBy = Username("Mark"),
          waitTime = Duration.ofMillis(2345),
          flipTime = Duration.ofMillis(7655)
        ),
        FlipResult(
          result = TAILS,
          coinStyle = CoinStyle("germany"),
          flippedBy = Username("Mark"),
          waitTime = Duration.ofMillis(23112),
          flipTime = Duration.ofMillis(5342)
        ),
        FlipResult(
          result = TAILS,
          coinStyle = CoinStyle("germany"),
          flippedBy = Username("Mark"),
          waitTime = Duration.ofMillis(23112),
          flipTime = Duration.ofMillis(5342)
        ),
      )
    ),
    options = listOf(
      UncertaintyOption(OptionName("EU4"), coinStyle = CoinStyle("first_world_war")),
      UncertaintyOption(OptionName("Civ VI"), coinStyle = CoinStyle("eu_germany")),
      UncertaintyOption(OptionName("HoI4"), coinStyle = CoinStyle("germany"), eliminated = true),
      UncertaintyOption(OptionName("Stellaris"), coinStyle = CoinStyle("usa_trump"))
    ),
  ),
  Uncertainty(
    id = UncertaintyId("winner"),
    name = "[TEST] Already has a winner",
    rules = UncertaintyRules(bestOf = 5, finalTwoHeadToHead = true),
    currentRound = Round(
      coinStyle = CoinStyle("first_world_war"),
      data = HeadToHeadRound(
        headsOption = OptionName("EU4"),
        tailsOption = OptionName("Civ VI"),
      )
    ),
    options = listOf(
      UncertaintyOption(OptionName("EU4"), coinStyle = CoinStyle("first_world_war")),
      UncertaintyOption(OptionName("Civ VI"), coinStyle = CoinStyle("eu_germany")),
      UncertaintyOption(OptionName("HoI4"), coinStyle = CoinStyle("germany"), eliminated = true),
      UncertaintyOption(OptionName("Stellaris"), coinStyle = CoinStyle("usa_trump"), eliminated = true)
    ),
    winner = Winner(OptionName("Civ VI"), HEADS, CoinStyle("eu_germany"))
  ),
  Uncertainty(
    id = UncertaintyId("bestof1"),
    name = "[TEST] Best of 1",
    rules = UncertaintyRules(bestOf = 1, finalTwoHeadToHead = true),
    currentRound = Round(
      coinStyle = CoinStyle("eu_germany"),
      data = MeaningfulVoteRound(OptionName("EU4")),
    ),
    options = listOf(
      UncertaintyOption(OptionName("EU4"), coinStyle = CoinStyle("first_world_war")),
      UncertaintyOption(OptionName("Civ VI"), coinStyle = CoinStyle("eu_germany")),
      UncertaintyOption(OptionName("HoI4"), coinStyle = CoinStyle("germany")),
    ),
  ),
  Uncertainty(
    id = UncertaintyId("h2h"),
    name = "[TEST] Head to Head",
    rules = UncertaintyRules(bestOf = 1, finalTwoHeadToHead = true),
    currentRound = Round(
      coinStyle = CoinStyle("japan"),
      data = HeadToHeadRound(
        headsOption = OptionName("WoW"),
        tailsOption = OptionName("HoI4"),
      ),
      results = listOf()
    ),
    options = listOf(
      UncertaintyOption(OptionName("WoW"), coinStyle = CoinStyle("japan")),
      UncertaintyOption(OptionName("EU4"), coinStyle = CoinStyle("japan"), eliminated = true),
      UncertaintyOption(OptionName("Civ VI"), coinStyle = CoinStyle("eu_germany"), eliminated = true),
      UncertaintyOption(OptionName("HoI4"), coinStyle = CoinStyle("germany")),
      UncertaintyOption(OptionName("Stellaris"), coinStyle = CoinStyle("usa_trump"), eliminated = true)
    ),
  ),
)
