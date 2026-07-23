package com.wwopenfare.emvlibrary

import android.content.Context
import kotlin.experimental.and

/*
Comprises this EMV Tag parameters and (if this tag is a template)
the list of embedded EMV Tags of the next level (rank)
Essentially, represents the tag tree up to the last elementary EMV Tag
*/

class EmvTag (
    private val rawData: ByteArray,     // used by the constructor to build the EMV Tags tree
                                //      it must be exactly one EMV Tag of Rank 0
                                //      (otherwise - an error)
    rawDataIndex: Int,      // position in the raw data to start looking for EMV Tags
    expectedLength: Int,    // length of the rest of rawData after rawDataIndex to look for
                                //      0 means up too the end of rawData
    private val rank: Int,               // the current rank of EMV Tag Tree, i.e of this EMV Tag object
                                // if rank = -1 - a fake erroneous EnvTag object will be constructed,
                                //          to avoid null
    private val context: Context
    )
{
    // to be determined in init
    private var emvTagCode: ByteArray = byteArrayOf()
    private var emvTagLongCode = 0L // to manage comparisons
    private var emvTagValueStart: Int = 0   // position of the tag value in Raw Data
    var emvTagValueLength: Int  = 0 // tag body length in Bytes
    var emvTagLengthLength: Int = 0 // length in Bytes of tag length
    private var isTemplate: Boolean = true  // false means elementary tag
    var emvTagError: String = "This is a valid tag"    // mainly for debug purposes
    var isSourceDataError: Boolean = false
    val myTags: MutableList<EmvTag> = mutableListOf() // the list of embedded tags of the next rank
    var elementaryEmvTagValueBin = byteArrayOf()
    var elementaryEmvTagValueString= ""
    var elementaryEmvTagValueHex="" // each byte as two printable hex digits-chars
    var hasPans: Boolean = false // does this tag or its lower rank tags have at least one tag with PAN?
    var myPan: String = "" // PAN stored with this tag. Nay be "". For template tag, this is PAN of one of it elements
    var hasCardholderName = false // false if there is no cardholder name in this tag or there is but it does not seem genuine
    var myCardholderName = ""  // non-empty only if it looks genuine
    private var hasFormFactorIndicator = false
    private var myFormFactorIndicator = byteArrayOf()
    var pdolBody = byteArrayOf()  // non empty means PDOL exists
    var cdol1Body = byteArrayOf() // non empty means CDOL1 exists

    init {
        require(rank>= -1) {context.getString(R.string.emv_syntx_1)}
        if (rank >=0) {
            require (rawData.size > 5) {context.getString(R.string.emv_syntx_2)}
            require (rawDataIndex in rawData.indices) {context.getString(R.string.emv_syntx_3)}
            require (expectedLength>=0) {context.getString(R.string.emv_syntx_4)}
        }
        var emvTagCodeLength = 1
        var currentIndex = rawDataIndex
        var lengthToLook = expectedLength
        if (expectedLength == 0) lengthToLook = rawData.size - currentIndex

        if (rank == -1) { // create a dummy EMV Tag with error (to avoid nulls)
            emvTagError = context.getString(R.string.emv_valid_tag)
            isSourceDataError = true
        }

        if (!isSourceDataError) {
            emvTagCodeLength = getMyTagCodeLength(rawData, currentIndex)
            if (emvTagCodeLength <= 0) {
                emvTagError = context.getString(R.string.emv_err_2) + rank
                isSourceDataError=true
            }
        }
        if (!isSourceDataError) {
            isTemplate = isMyTagTemplate(rawData, currentIndex)
            emvTagCode = ByteArray(emvTagCodeLength) {i -> rawData[currentIndex + i]}
            emvTagLongCode = makeLongCode(emvTagCode)
            currentIndex += emvTagCodeLength
            lengthToLook -= emvTagCodeLength
            emvTagLengthLength = getMyTagLengthLength(rawData, currentIndex)
            if (emvTagLengthLength <= 0) {
                emvTagError= context.getString(R.string.emv_error_3) + rank
                isSourceDataError=true
            }
        }

        if (!isSourceDataError) {
            emvTagValueLength = getMyTagValueLength(rawData, currentIndex, emvTagLengthLength)
            if (emvTagValueLength < 0) {
                emvTagError =
                    context.getString(R.string.emv_error_4)+emvTagValueLength+
                            ". Rank $rank"
                isSourceDataError = true
            }
        }
        if (!isSourceDataError) {
            currentIndex+=emvTagLengthLength
            lengthToLook-= emvTagLengthLength

            if (emvTagValueLength > lengthToLook) {
                emvTagError=
                    context.getString(R.string.emv_err_5) +  
                            "length=$emvTagValueLength, rank=$rank"
                isSourceDataError = true
            }
        }

        if (!isSourceDataError) {
            emvTagValueStart = currentIndex
            if(isTemplate) buildMyTagList(emvTagValueStart, emvTagValueLength)
            else { // this is an elementary tag
                elementaryEmvTagValueBin = ByteArray(emvTagValueLength)
                    {i -> rawData[emvTagValueStart + i]}
                elementaryEmvTagValueString = valueToString(elementaryEmvTagValueBin)
                elementaryEmvTagValueHex = toHex(elementaryEmvTagValueBin)
                lookForPan()
                lookForCardholderName()
                lookForFormFactor()
                lookForPdol()
                lookForCdol1()
             }
        }
    } // init end

    private fun lookForPan() {
        if( hasTagCode(CODE_PAN_5A) ) {
            myPan = elementaryEmvTagValueHex
            hasPans = true
        } else {
            if( hasTagCode(CODE_TRACK2_57) or hasTagCode(CODE_TRACK2_9F6B) ) {
                myPan = getPanFromTrack2(elementaryEmvTagValueHex)
                hasPans =true
            } else {
                if (hasTagCode(CODE_TRACK1_56)) {
                    myPan = getPanFromTrack1(
                        elementaryEmvTagValueString,
                        elementaryEmvTagValueBin)
                    hasPans = true
                }
            }
        }
        if (myPan.length < 16) {
            hasPans = false
            myPan = ""
        }
    }

    private fun lookForCardholderName() {
        if( hasTagCode(CODE_CH_NAME_5F20) ) {
            if ((elementaryEmvTagValueString.length > 4)
                and
                (countBads(elementaryEmvTagValueString) +4 <
                        elementaryEmvTagValueString.length)
                    // non-printable symbols already changed to '?'
                ) {
              hasCardholderName = true
              myCardholderName = elementaryEmvTagValueString
            }
        }
    }

    private fun lookForFormFactor() {
        if( hasTagCode(FORM_FACTOR_9F6E) ) {
            hasFormFactorIndicator = true
            myFormFactorIndicator = elementaryEmvTagValueBin
        }
    }

    private fun lookForPdol() {
        if( hasTagCode(CODE_PDOL_9F38) ) {
            pdolBody = elementaryEmvTagValueBin
        }
    }

    private fun lookForCdol1() {
        if( hasTagCode(CODE_CDOL1_8C) ) {
            cdol1Body = elementaryEmvTagValueBin
        }
    }

    private fun getPanFromTrack2 (track2: String): String {
        // get PAN from Track 2 equivalent data
        var separatorPos=track2.indexOf('d')
        if ((separatorPos==0) or (separatorPos > 19))  separatorPos =16
        if (separatorPos > track2.length) return ""
        return track2.subSequence(0 until separatorPos).toString()
    }

    private fun getPanFromTrack1 (track1: String, track1Bytes: ByteArray): String {
        val start =  track1Bytes.indexOf(0x42.toByte()) +1 // pan starts after 0x42
        val end = track1Bytes.indexOf(0x5e.toByte()) - 1
        if ((end < start) or (end > start +19) )
            return ""
        if (end-start < 15) return ""
        return track1.subSequence(start..end).toString()
    }



    fun findTag(tagCodeToFind: ByteArray): EmvTag {
        // this tag must be a template.
        // The function looks for first occurance (should be only one) of internal tag in this tag
        //   - looks in the next rank only
        // if not found - returns this tag with error flag and message

        val longCodeToFind = makeLongCode(tagCodeToFind)
        if (!isTemplate) {
            emvTagError = context.getString(R.string.emv_error_7)
            return this
        }
        for (t in myTags) {
            if (t.emvTagLongCode==longCodeToFind) return t
        }
        emvTagError = context.getString(R.string.emv_error_8) + toHex(tagCodeToFind)
        return this
    }

    private fun buildMyTagList (rawIndex: Int, lengthToLook:Int) {
        var i = 0
        while ( !isSourceDataError && (i < lengthToLook)) {

            val currentEmvTag = EmvTag(rawData, rawIndex+i,
                lengthToLook-i, rank+1, context )
            if (!currentEmvTag.isSourceDataError) {
                myTags.add(currentEmvTag)
                if(currentEmvTag.hasPans) {
                    myPan = currentEmvTag.myPan
                    hasPans = true
                }
                if(currentEmvTag.hasCardholderName) {
                    myCardholderName = currentEmvTag.myCardholderName
                    hasCardholderName = true
                }
                if(currentEmvTag.hasFormFactorIndicator) {
                    myFormFactorIndicator = currentEmvTag.myFormFactorIndicator
                    hasFormFactorIndicator = true
                }
                if(currentEmvTag.pdolBody.isNotEmpty())
                    pdolBody =  currentEmvTag.pdolBody
                if(currentEmvTag.cdol1Body.isNotEmpty())
                    cdol1Body =  currentEmvTag.cdol1Body

                i += currentEmvTag.emvTagCode.size +
                        currentEmvTag.emvTagLengthLength +
                        currentEmvTag.emvTagValueLength
            } else {
                isSourceDataError = true
                emvTagError = "Error 6. During making a list of tags of rank ${rank+1}"
            }
        }
    }

    override fun toString(): String {
        if (isSourceDataError) return "$emvTagError\n"
        var rankIndent = "\n"
        if (rank>0) {
            for (i in 1..rank) rankIndent += "    "
        }
        return if (rank>=0) { // -1 - dummy ENV Tag
            var text = rankIndent + toHexTagCode(emvTagCode) + " " + tagName(emvTagCode) +
                    ", length=$emvTagValueLength"
            text += if (isTemplate) toHexTemplateTagValue()
            else rankIndent + "     " + elementaryEmvTagValueHex +
                    rankIndent + "     " + elementaryEmvTagValueString
            text
        } else "No EMV Tag. Error: $emvTagError"
    }

    private fun tagName(emvTagCode: ByteArray): String {
        return tagNames[makeLongCode(emvTagCode)] ?: ""
    }

    private fun toHexTemplateTagValue(): String {
        var s = ""
        for (x in myTags) {
                s+=x.toString()
            }
        return s
    }

    private fun toHexTagCode(bytes: ByteArray): String {
        return if (bytes.size == 2) toHex(bytes)
                    else toHex(bytes) + "  "
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getMyTagValueLength(buffer: ByteArray, startPos: Int, lengthLength: Int): Int {
        if (lengthLength == 1)
            return buffer[startPos].toInt() // bit 8 in this case is 0. Length is < 128

        // if there are more than 1 byte of value length - the length is coded in the 2nd and next bytes
        return (
            if (lengthLength == 2)
                buffer[startPos + 1].toUByte().toInt()
            else buffer[startPos + 1].toUByte().toInt() * 256 +
                    buffer[startPos + 2].toUByte().toInt()
                // 1 byte coded  length < 256
                )
        // 2 or 3 coded bytes. We assume, always less than 4
    }



    private fun getMyTagLengthLength(buffer: ByteArray, startLengthPos: Int): Int {
        var lengthOfLength = 1 // by default

        if (buffer[startLengthPos] < 0) {
            // bit 8==1 indicates that the next 7 bits code the length of the length code
            lengthOfLength =
                (buffer[startLengthPos] and 3) + 1
                    // number of bytes that represent tag length (not more than 3)
        }
        return lengthOfLength
    }

    private fun isMyTagTemplate(buffer: ByteArray, startPos: Int): Boolean {
        return buffer[startPos] and 0x20.toByte() > 0
        // EMV Book 3 annex B. Bit 6 = 1 means template
    }

    private fun valueToString(bytes: ByteArray): String {
        var s ="\""
        for (element in bytes) {
            val char = element.toInt().toChar()
            if(char.isLetterOrDigit() or (char.code in 0x20..0x2f)) s+=char
            else s+="?"
        }
        return s + "\""
    }

    fun hasTagCode (toCompare: ByteArray) : Boolean {
        if (emvTagCode.isEmpty()) return false // just in case
        return emvTagLongCode == makeLongCode(toCompare)
    }
    companion object {
        fun getMyTagCodeLength(buffer: ByteArray, startLengthPos: Int): Int {
            // Find tag code length
            var codeLength = 1
            if (buffer[startLengthPos] and 0x1F.toByte() == 0x1F.toByte()) {
                // tags with many-byte code have "1F" in the first byte of the code. EMV Book 3 Annex B
                codeLength++
                while (buffer[startLengthPos + codeLength - 1] < 0) {
                    // bit 8 = 1 indicates that another byte of the tag code follows
                    codeLength++
                }
            }
            return codeLength
        }

        val CODE_FCI_TEMPLATE_6F = hexBytes(0x6F)
        val CODE_FCI_PROPR_A5 = hexBytes(0xA5)
        val CODE_KERNEL_ID_9F2A = hexBytes(0x9F, 0x2A)
        val CODE_FCI_IDD_BF0C = hexBytes(0xBF, 0x0C)
        val CODE_DIR_ENTRY_61 = hexBytes(0x61)
        val CODE_APP_LABEL_50 = hexBytes(0x50)
        val CODE_ADF_NAME_4F = hexBytes(0x4F)
        val CODE_DF_NAME_84 = hexBytes(0x84)
        val CODE_LANG_PREF_5F2D = hexBytes(0x5F, 0x2D)
        val CODE_REC_70 = hexBytes(0x70)
        val CODE_TRACK2_57 = hexBytes(0x57)
        val CODE_TRACK1_DISCR_9F1F = hexBytes(0x9F, 0x1F) // no use
        val CODE_PDOL_9F38 = hexBytes(0x9F, 0x38)
        val CODE_ICC_Public_Key_Certificate = hexBytes(0x9F, 0x46)
        val CODE_TRACK2_9F6B = hexBytes(0x9F, 0x6B)
        val CODE_TRACK1_56 = hexBytes(0x56)
        val CODE_PAN_5A = hexBytes(0x5A)
        val CODE_GPO_RESP_FORMAT2_77 = hexBytes(0x77)
        val CODE_AFL_94 = hexBytes(0x94)
        val CODE_CH_NAME_5F20 = hexBytes(0x5F, 0x20)
        val FORM_FACTOR_9F6E = hexBytes(0x9F, 0x6E)
        val CODE_CDOL1_8C = hexBytes(0x8C)


        // AID Name Prefixes
        val VISA_AID_PREF       = hexBytes(0xA0, 0x00, 0x00, 0x00, 0x03)
        val MASTERCARD_AID_PREF = hexBytes(0xA0, 0x00, 0x00, 0x00, 0x04)
        val INTERAC_AID_PREF    = hexBytes(0xA0, 0x00, 0x00, 0x02, 0x77)

        val JCB_AID_PREF        = hexBytes(0xA0, 0x00, 0x00, 0x00, 0x65)  // A000000065
        val AMEX_AID_PREF       = hexBytes(0xA0, 0x00, 0x00, 0x00, 0x25)  // A000000025
        val CUP_AID_PREF        = hexBytes(0xA0, 0x00, 0x00, 0x03, 0x33)  // A000000333
        val DINERS_AID_PREF     = hexBytes(0xA0, 0x00, 0x00, 0x01, 0x52)  // A000000152
        val EFTPOS_AID_PREF     = hexBytes(0xA0, 0x00, 0x00, 0x03, 0x84)  //


        fun makeLongCode(emvTagCode: ByteArray): Long {
            var long :Long  = emvTagCode[0].toUByte().toLong()
            if (emvTagCode.size>1) {
                for (i in 1 until emvTagCode.size)
                    long = long * 256 + emvTagCode[i].toUByte().toInt()
            }
            return long
        }

        val tagNames: Map <Long, String> = mapOf(
            makeLongCode(CODE_FCI_TEMPLATE_6F) to "FCI Template",
            makeLongCode (CODE_FCI_PROPR_A5) to "FCI Proprietary Template",

            makeLongCode(CODE_KERNEL_ID_9F2A) to "Kernel Identifier",
            makeLongCode(CODE_FCI_IDD_BF0C) to "FCI Issuer Discretionary Data",
            makeLongCode(CODE_DIR_ENTRY_61) to "Directory Entry",
            makeLongCode(CODE_APP_LABEL_50) to "Application Label",
            makeLongCode(CODE_ADF_NAME_4F) to "ADF Name",
            makeLongCode(CODE_DF_NAME_84) to "Dedicated File Name",
            makeLongCode(CODE_LANG_PREF_5F2D) to "Language Preference",
            makeLongCode(CODE_PDOL_9F38) to "PDOL",
            makeLongCode(CODE_REC_70) to "Read Record Response Message Template",
            makeLongCode(CODE_TRACK2_57) to "Track 2 Equivalent Data",
            makeLongCode(CODE_TRACK1_56) to "Track 1 Equivalent Data",
            makeLongCode(CODE_PAN_5A) to "Primary Account Number ",
            makeLongCode(CODE_GPO_RESP_FORMAT2_77) to "Response Message Template Format 2",
            makeLongCode(CODE_AFL_94) to "Application File Locator",
            0x42L to "IIN",
            0x5aL to "PAN",
            makeLongCode(CODE_CH_NAME_5F20) to "Cardholder Name",
            0x5f24L to "Application Expiration Date",
            0x5f25L to "Application Effective Date",
            0x5f28L to "Issuer Country Code",
            0x5f30L to "Service Code",
            0x5f34L to "Application PAN Sequence Number",
            0x5f42L to "Address",

            0x5f44L to "Application image",
            0x5f56L to "Issuer Country Code",

            0x82L to "Application Interchange Profile",
            0x87L to "Application Priority Indicator",
            makeLongCode(CODE_CDOL1_8C) to "CDOL1",
            0x8dL to "CDOL2",
            0x8eL to "CVM List",
            0x8fL to "Certification Authority Public Key Index",

            0x90L to "Issuer Public Key Certificate",
            0x92L to "Issuer Public Key Remainder",
            0x9f0dL to "Issuer Action Code - Default",
            0x9f0eL to "Issuer Action Code - Denial",
            0x9f07L to "Application Usage Control",
            0x9f08L to "Application Version Number",
            0x9f0fL to "Issuer Action Code - Online",
            0x9f10L to "Issuer Application Data",
            0x9f11L to "Issuer Code Table Index",
            0x9f12L to "Application Preferred Name",
            makeLongCode(CODE_TRACK1_DISCR_9F1F )  to "Track 1 Discretionary Data",
            0x9f19L to "DDOL (deprecated)",
            0x9f20L to "Track 2 Discretionary Data",
            0x9f24L to "Payment Account Reference",
            0x9f26L to "Application Cryptogram",
            0x9f27L to "Cryptogram Information Data",
            0x9f32L to "Issuer Public Key Exponent",
            0x9f36L to "Application Transaction Counter",
            0x9f42L to "Application Currency Code",
            0x9f44L to "Application Currency Exponent",
            0x9f46L to "ICC Public Key Certificate",
            0x9f47L to "ICC Public Key Exponent",
            0x9f48L to "ICC Public Key Remainder",
            0x9f4aL to "SDA Tag List",
            0x9f4bL to "Signed Dynamic Application Data",
            0x9f4dL to "Log Entry",
            0x9f56L to "Issuer Authentication Indicator",
            0x9f5aL to "Application Program ID",
            0x9f62L to "Encrypted PIN",
            0x9f63L to "EPUNATC",
            0x9f64L to "NATC - Track1",
            0x9f65L to "PCVC3 - Track2",
            0x9f66L to "Terminal Transaction Qualifiers",
            0x9f67L to "NATC - Track2",
            0x9f69L to "Card Authentication Related Data",
            makeLongCode(CODE_TRACK2_9F6B)  to "Track 2 Data",
            0x9f6cL to "Card Transaction Qualifiers",
            makeLongCode(FORM_FACTOR_9F6E) to "Form Factor Indicator",
            0x9f70L to "Card Interface Capabilities",
            0xdf20L to "Issuer Proprietary Bitmap",
            0xdf62L to "DDA Component D1"
        )
        fun countBads(s: String): Int {
            var count = 0 // counts of question marks
            // Check if the string contains '?'
            var lastIndex = s.indexOf('?', 0)
            while (lastIndex >= 0) {
                count += 1
                // Find the next ocurence
                lastIndex = s.indexOf('?', lastIndex + 1)
            }
            return count
        }
    }

}