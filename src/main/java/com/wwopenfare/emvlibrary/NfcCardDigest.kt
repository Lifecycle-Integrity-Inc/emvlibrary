package com.wwopenfare.emvlibrary

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
// ... other imports ...
// import kotlin.text.append // No longer explicitly needed due to SpannableStringBuilder.append

data class EmvDirectory(
    val aid: ByteArray,
    val aidHex: String,       // converted from binary to printable hex digits
    var label: String,     // application label
    val kernelId: String,  // converted from binary to printable hex digits
    val cardType: CardType, // enum
)

data class RetrievedEmvTag(
    // this object is filled in during the analysis of APDU response
    val tittle: String, // like "PPSE APDU", etc.
    val apduResponse: ByteArray,
    val sws: String, // status words
    var emvTag: EmvTag,
    val isError: Boolean, // either APDU error or inconsistent data
    val errorDescription: String, // non empty if isError.
    val apduMsec: Long,
    val apduRequest: ByteArray = byteArrayOf(), // optional - APDU Request
)

enum class CardType {
    UNKNOWN, VISA, MASTERCARD, INTERAC, AMEX, DINERS, JCB, CUP, EFTPOS
}

class NfcCardDigest(val context: Context) {
    // comprises main data captured during the card reading
    // can present this data to be displayed in an activity

    var cardPan = "" // PAN or DPAN
    private var cardholderName = "" // empty means that it does not seem genuine
    //var safeFormFactor = false // if false - PAN verification is required
    var totalTransieveMsec: Long = 0 // total for all APDU transieves
    var totalTransceiveBytes: Int = 0 // total for all APDU responces, counted by the upper level tag length
    var transceiveSpeedKbitsPerSec = 0.0
    var gpoTransieveMsec: Long = 0 // included in total
    var generateAcTransceiveMsec: Long = 0
    val cardDirectories = mutableListOf<EmvDirectory>()
    var usedDirectoryIndex = 0 // the directory that was used in this tap
    val retrievedTags = mutableListOf<RetrievedEmvTag>()
    private val summaryLines = mutableListOf<String>()
    private val abnormalities = mutableListOf<String>()
    private val badThings = mutableListOf<String>()
    val rawApdus = mutableListOf<String>()
    private val PURCHASE_FULL = context.getString(R.string.purchase_full_version)


    fun summaryLog(s: String) {
        summaryLines.add(s)
    }

    fun abnormalLog(s: String) {
        abnormalities.add(s)
    }

    fun badThingsLog(s: String) {
        badThings.add(s)
    }


    fun rawApduLog(s: String) { // Note: In your original code, this was adding to badThings. Assuming it should be rawApdus.
        rawApdus.add(s)
    }

    private fun summariesText(): CharSequence {
        val builder = SpannableStringBuilder()
        appendHeadingOne("\nCard Summary", builder) // Includes leading newline

        if (summaryLines.isNotEmpty()) {
            summaryLines.forEachIndexed { _, line -> // index not needed here
                builder.append("\n")
                if (line.startsWith("POSSIBLE PRIVACY VIOLATION")) {
                    appendWarning(line, builder)
                } else {
                    builder.append(line)
                }
            }
        }
        return builder
    }

    private fun summariesTextFree(): CharSequence {
        val builder = SpannableStringBuilder()
        val maxItems = 4
        appendHeadingOne("\nCard Summary", builder)

        summaryLines.take(maxItems).forEach { line ->
            builder.append("\n")
            builder.append(line)
        }

        if (summaryLines.size > maxItems) {
            builder.append(if (builder.isNotEmpty() && !builder.endsWith("\n")) "\n" else "")
            builder.append("... ")
            appendHighlighted(PURCHASE_FULL, builder) // Changed from setWarningStyle to setHighlightedStyle based on var name
        }
        return builder
    }

    private fun abnormalitiesText(): CharSequence {
        if (abnormalities.isEmpty()) {
            return " None."
        }
        val builder = SpannableStringBuilder()
        abnormalities.forEachIndexed { index, line ->
            if (index > 0) {
                builder.append("\n")
            }
            // Original code commented out setWarningStyle here. Keeping it as plain append.
            builder.append(line)
        }
        return builder
    }

    private fun badThingsText(): CharSequence {
        if (badThings.isEmpty()) {
            return " None."
        }
        val builder = SpannableStringBuilder()
        badThings.forEachIndexed { index, line ->
            if (index > 0) {
                builder.append("\n")
            }
            // Applying warning style to each bad thing as per original logic
            builder.append(line)
        }
        return builder
    }

    private fun rawApdusText(): CharSequence {
        if (rawApdus.isEmpty()) return " None."
        val builder = SpannableStringBuilder()
        for (s in rawApdus) builder.append("\n").append(s)
        return builder
    }

    private fun rawApduRequests(): CharSequence {
        if (rawApdus.isEmpty()) return " None."
        val builder = SpannableStringBuilder()
        val delimiter = "\nAPDU RESPONSE:" // The marker where we want to stop reading
        for (fullLog in rawApdus) {
            // fullLog looks like: "\nTitle: APDU REQUEST: ...\nAPDU RESPONSE: ..."
            val endIndex = fullLog.indexOf(delimiter)
            val requestOnly = if (endIndex != -1) {
                // Extract everything up to the response marker
                fullLog.substring(0, endIndex)
            } else {
                // Fallback: If for some reason the marker isn't there, keep the whole line
                fullLog
            }
            builder.append("\n").append(requestOnly)
        }
        return builder
    }

    private fun tagsText(): CharSequence {
        if (retrievedTags.isEmpty()) {
            return " None."
        }
        val builder = SpannableStringBuilder()
        for (t in retrievedTags) {
            builder.append("\n")
            appendHighlighted("APDU Request: ", builder)
            builder.append(t.apduRequest.joinToString("") { "%02x".format(it) })

            val responseHeaderString =
                "\n${t.tittle}. Transceive Time=${t.apduMsec} ms. SW1/SW2: ${t.sws}. \nAPDU Response Content:"
            appendHighlighted(responseHeaderString, builder)
            builder.append(t.emvTag.toString())
            appendHighlighted("\n---- End of APDU Response Content ----\n\n", builder)
        }
        return builder // No need for "if (builder.isEmpty())" check due to initial check
    }

    private fun limitedTagsText(): CharSequence {
        if (retrievedTags.isEmpty()) return " None."
        val builder = SpannableStringBuilder() // Use SpannableStringBuilder for consistency
        for (t in retrievedTags) {
            if (builder.isNotEmpty()) builder.append("\n") // Add newline between entries

            if (!(t.emvTag.hasPans or t.emvTag.hasCardholderName)) {
                builder.append("==== ${t.tittle}. Transceive Time=${t.apduMsec} msec. SW1/SW2=${t.sws}. \nResponse content:")
                builder.append(t.emvTag.toString())
                builder.append(context.getString(R.string.end_of_response_content)) // Removed extra \n from original
            } else {
                builder.append("==== ${t.tittle}. SW1/SW2=${t.sws}. \n ***** CONTENT IS HIDDEN *****\n")
                builder.append(context.getString(R.string.retap_card)) // Removed extra \n from original
            }
        }
        return builder // .toString().ifEmpty { " None." } // ifEmpty check handled by initial return
    }

    private fun freeAppTagsText(): CharSequence {
        if (retrievedTags.isEmpty()) {
            return " None."
        }
        val builder = SpannableStringBuilder()
        val limit = 3
        var count = 0

        retrievedTags.forEach { t ->
            if (builder.isNotEmpty()) {
                builder.append("\n")
            }
            builder.append("==== ${t.tittle}. SW1/SW2=${t.sws}. ")

            if (!(t.emvTag.hasPans || t.emvTag.hasCardholderName) && count < limit) {
                builder.append("Response content:")
                builder.append(t.emvTag.toString())
                builder.append("\n---- End of response content ----.\n")
                count++
            } else {
                builder.append(context.getString(R.string.content_is_hidden))
                appendWarning(PURCHASE_FULL, builder) // Used appendWarning as per original
            }
        }
        return if (builder.isEmpty()) " None." else builder
    }

    private fun directories(): CharSequence {
        val builder = SpannableStringBuilder()
        appendHeadingTwo("\nDirectories:", builder) // Add leading newline here

        var i = 0
        for (dir in cardDirectories) {
            builder.append("\n${i}. AID: ${dir.aidHex}, Label: ${dir.label}, Kernel ID: ${dir.kernelId}.")
            i++
        }

        if (i == 0) {
            builder.append("\nNone.\n")
        } else {
            builder.append("\nDirectory #${usedDirectoryIndex} was used in this tap.\n")
        }
        return builder
    }


    private fun tapCard(versionName: String): CharSequence {
        val builder = SpannableStringBuilder()

        val initialMessagePart1 = if (usedDirectoryIndex < cardDirectories.size - 1) {
            context.getString(R.string.next_dir_tap)
        } else {
            context.getString(R.string.tap_another)
        }
        appendWarning(initialMessagePart1, builder)
        //appendWarningBig(context.getString(R.string.please_rate), builder)
        builder.append("\n\nApp version: $versionName\n")
        return builder
    }

    fun prepareFullDisplay(versionName: String): CharSequence {
        val builder = SpannableStringBuilder()

        appendHighlighted(context.getString(R.string.copy_clip), builder)
        builder.append("\n\n")
        builder.append(context.getString(R.string.present_form))
        builder.append("\n")

        builder.append(summariesText())
        builder.append(directories())

        appendHeadingOne("End of Card Summary", builder) // Includes leading newline
        builder.append("\n\n")

        appendWarning("Retrieved PAN or DPAN: ", builder)
        appendHighlighted(cardPan, builder) // You already had this one!
        builder.append(".\n\n")

        builder.append(tagsText())
        if (builder.isNotEmpty() && !builder.toString().endsWith("\n")) { // Ensure newline if tagsText didn't add one
            builder.append("\n")
        }

        appendWarning("Curious stuff:\n", builder) // Assuming WarningStyle for "Curious stuff:"
        builder.append(abnormalitiesText())
        builder.append("\n\n")

        appendHeadingTwo("\nRaw APDU Requests:", builder)
        builder.append(rawApduRequests())
        builder.append("\n\n")

        builder.append(tapCard(versionName))
        return builder
    }

    fun prepareLimitedDisplay(comment: String, versionName: String): CharSequence {
        val builder = SpannableStringBuilder()

        appendHighlighted(context.getString(R.string.copy_clip),builder)
        builder.append("\n\n")
        builder.append(comment)
        builder.append("\n")

        builder.append(summariesText())
        builder.append(directories())

        appendHeadingOne("\nEnd of Summary", builder)
        builder.append("\n\n")

        builder.append(limitedTagsText())
        builder.append("\n")

        appendWarning("\nCurious stuff: ", builder)
        builder.append(abnormalitiesText())
        builder.append("\n\n")

        appendHeadingTwo("\nRaw APDU Requests:", builder)
        builder.append(rawApduRequests())
        builder.append("\n\n")

        builder.append(tapCard(versionName))

        return builder
    }

    fun prepareFreeAppDisplay(comment: String, versionName: String): CharSequence {
        val builder = SpannableStringBuilder()

        builder.append(context.getString(R.string.copy_clip))
        builder.append("\n\n")
        builder.append(comment)
        builder.append("\n")

        builder.append(summariesTextFree())
        builder.append(directories())
        builder.append("\n")
        appendHeadingOne("\nEnd of Summary", builder)
        builder.append("\n\n")
        builder.append(freeAppTagsText())
        builder.append("\n")

        appendHeadingOne("\nCurious stuff:", builder)
        builder.append(abnormalitiesText())
        builder.append("\n\n")
        builder.append(tapCard(versionName))

        return builder
    }

    fun prepareErrorDisplay(versionName: String): CharSequence {
        val builder = SpannableStringBuilder()

        builder.append("\n")
        appendHighlighted(context.getString(R.string.copy_clip), builder)
        builder.append("\n\n")
        builder.append(context.getString(R.string.present_form))
        builder.append("\n\n")

        builder.append(summariesText())
        builder.append(directories())
        appendHeadingOne("\nEnd of Summary", builder)
        builder.append("\n\n")
        builder.append(tagsText())

        // For "Unexpected stuff:${badThingsText()}\n" with HeadingOneStyle on the whole block:
        appendWarning("Unexpected stuff: ",builder )
        builder.append(badThingsText()) // badThingsText() already applies WarningStyle to its items
        builder.append("\n")
        // Applying HeadingOneStyle to the entire block. This will make "Unexpected stuff:" H1,
        // and also the content from badThingsText() (which is already warning-styled) will get H1 sizing.

        appendWarning("\nCurious stuff: ", builder)
        // Style abnormalities content as H1
        val abnormalitiesContent = abnormalitiesText()
        builder.append(abnormalitiesContent)
        builder.append("\n\n")

        appendHeadingTwo("\nRaw APDU log:", builder)
        builder.append(rawApdusText())
        builder.append("\n\n")
        builder.append(tapCard(versionName))
        return builder
    }

    fun prepareIOErrorDisplay(versionName: String): CharSequence {
        val builder = SpannableStringBuilder()
        builder.append("\n")
        builder.append(context.getString(R.string.copy_clip))
        builder.append("\n\n")
        builder.append(context.getString(R.string.present_form))
        builder.append("\n\n")
        builder.append(summariesText())
        builder.append(directories())

        appendHeadingOne("\nEnd of Summary", builder)
        builder.append("\n\n")

        appendWarning("Unexpected stuff: ", builder) // Style prefix with H1
        builder.append(badThingsText()) // Append content with its own style
        builder.append("\n")

        appendWarning("\nCurious stuff: ", builder)
        builder.append(abnormalitiesText())
        builder.append("\n\n")
        builder.append(tapCard(versionName))
        return builder
    }


    fun getPanFromAllData() {
        for (rTag in retrievedTags) {
            if (!rTag.isError) {
                if (rTag.emvTag.hasPans) {
                    cardPan = rTag.emvTag.myPan
                    summaryLines.add(context.getString(R.string.pan_from) + " " + rTag.tittle)
                    return
                }
            }
        }
        abnormalLog(context.getString(R.string.card_no_pan))
        badThingsLog(context.getString(R.string.card_no_pan) + "\n" + context.getString(R.string.card_not_activatd))
        cardPan = ""
    }

    fun getCardHolderNameFromAllData() {
        for (rTag in retrievedTags) {
            if (!rTag.isError) {
                if (rTag.emvTag.hasCardholderName) {
                    cardholderName = rTag.emvTag.myCardholderName
                    summaryLines.add(
                        "\nPOSSIBLE PRIVACY VIOLATION. Cardholder Name is retrieved from" +
                                " " + rTag.tittle + ". Is it genuine?\n"
                    )
                    abnormalLog("POSSIBLE PRIVACY VIOLATION. Cardholder Name may be genuine.")
                    return
                }
            }
        }
        cardholderName = ""
    }

    fun calcApduTimes() {
        totalTransieveMsec = 0
        totalTransceiveBytes = 0
        for (rt in retrievedTags) {
            totalTransieveMsec += rt.apduMsec
            totalTransceiveBytes += rt.apduResponse.size
        }
        transceiveSpeedKbitsPerSec =
            if (totalTransieveMsec > 5)
                (8.0 * totalTransceiveBytes.toDouble() / totalTransieveMsec.toDouble())
            else 0.0
    }

    fun assessFormFactor() {
        /********
         * do nothing. Form factor intepretation is complex and left behind.
         ********/
    }

    companion object {
        
        private const val HEADING_ONE_SIZE_FACTOR = 1.4f
        private const val HEADING_TWO_SIZE_FACTOR = 1.2f

        // --- Append Helper Functions (with styles inlined) ---
        fun appendHighlighted(newPiece: String, appendTo: SpannableStringBuilder) {
            if (newPiece.isEmpty()) return
            val spannableNewPiece = SpannableString(newPiece)
            // Inlined setHighlightedStyle
            spannableNewPiece.setSpan(
                ForegroundColorSpan(Color.BLUE),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableNewPiece.setSpan(
                StyleSpan(Typeface.ITALIC),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            appendTo.append(spannableNewPiece)
        }

        fun appendHeadingOne(newPiece: String, appendTo: SpannableStringBuilder) {
            if (newPiece.isEmpty()) return
            val spannableNewPiece = SpannableString(newPiece)
            // Inlined setHeadingOneStyle
            spannableNewPiece.setSpan(
                RelativeSizeSpan(HEADING_ONE_SIZE_FACTOR),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableNewPiece.setSpan(
                StyleSpan(Typeface.BOLD),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            appendTo.append(spannableNewPiece)
        }

        fun appendHeadingTwo(newPiece: String, appendTo: SpannableStringBuilder) {
            if (newPiece.isEmpty()) return
            val spannableNewPiece = SpannableString(newPiece)
            // Inlined setHeadingTwoStyle
            spannableNewPiece.setSpan(
                RelativeSizeSpan(HEADING_TWO_SIZE_FACTOR),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableNewPiece.setSpan(
                StyleSpan(Typeface.BOLD),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            appendTo.append(spannableNewPiece)
        }

        fun appendWarning(newPiece: String, appendTo: SpannableStringBuilder) {
            if (newPiece.isEmpty()) return
            val spannableNewPiece = SpannableString(newPiece)
            // Inlined setWarningStyle
            spannableNewPiece.setSpan(
                ForegroundColorSpan(Color.MAGENTA),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableNewPiece.setSpan(
                StyleSpan(Typeface.BOLD),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            appendTo.append(spannableNewPiece)
        }

        fun appendWarningBig(newPiece: String, appendTo: SpannableStringBuilder) {
            if (newPiece.isEmpty()) return
            val spannableNewPiece = SpannableString(newPiece)
            // Inlined setWarningBigStyle
            spannableNewPiece.setSpan(
                ForegroundColorSpan(Color.MAGENTA),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableNewPiece.setSpan(
                StyleSpan(Typeface.BOLD),
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableNewPiece.setSpan(
                RelativeSizeSpan(HEADING_TWO_SIZE_FACTOR), // Was HEADING_TWO_SIZE_FACTOR
                0, spannableNewPiece.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            appendTo.append(spannableNewPiece)
        }
    } /// end of companion
}