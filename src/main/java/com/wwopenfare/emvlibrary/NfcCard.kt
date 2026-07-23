package com.wwopenfare.emvlibrary

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import java.io.IOException
import java.util.*

class NfcCard(val tagFromIntent: Tag,
              val isoDep: IsoDep,
              val context: Context
              ) {

    private var digest = NfcCardDigest(context)
    var isGood = false // result of card exploration
    var isIoError = false
    private lateinit var pdol: Pdol
    private var cType = CardType.UNKNOWN

    // rstruct @Throws(IOException::class)
    fun explore(dirIndex: Int // the directory index to use, if possible
        ): NfcCardDigest {
        Log.d(LOG_NFC_CARD,"explore() start")
        // Called on NFC intent.
        // Within the NFC session
        // - Reads whatever is possible from the card
        // - Finds card PAN/DPAN in form of String
        // - Measures the NFC session time
        // nd fills all the above in NfcCardDigest object for further Display, record or use On Resume

        try {
            isoDep.connect()
        } catch  (e: IOException) {
            Log.e(LOG_NFC_CARD, "ISodep c0Onnect IO Error")
            isIoError = true
        }
        // rstruct communication with tag adapter is established. Start talking with card
        val tapStart = Date().time // in msec
        if (!isIoError) {
            if (explorePPSE() >= 0) { //successful retrieval of one or more directories. Also covers IO Error
                var index = dirIndex
                if (dirIndex >= digest.cardDirectories.size) {
                    // if another card is tapped and it has less dirrectories.
                    index = 0
                }
                digest.usedDirectoryIndex = index // to let mainActivity know
                val retrievedDirAdfTag = selectAdf(digest.cardDirectories[index].aid)
                // we read card data related to this directory entry only

                if (retrievedDirAdfTag.isError) { // also covers IO Error
                    logBads((context.getString(R.string.select_adf_error) +
                            retrievedDirAdfTag.errorDescription))
                    isGood = false
                } else { // ADF is fine
                    if (digest.cardDirectories[index].label.compareTo(DATA_ABSENT) == 0) {
                        // label is not specified in PPSE - get it from ADF
                        val labelTag =
                            retrievedDirAdfTag.emvTag.findTag(EmvTag.CODE_FCI_TEMPLATE_6F)
                                .findTag(EmvTag.CODE_FCI_PROPR_A5).findTag(EmvTag.CODE_APP_LABEL_50)
                        if (labelTag.hasTagCode(EmvTag.CODE_APP_LABEL_50)) { // found right tag
                            digest.cardDirectories[index].label =
                                "(from ADF) " + labelTag.elementaryEmvTagValueString
                        }
                    }
                    readAllData(retrievedDirAdfTag) // gets AFL from GPO and reads all records
                    if (!isIoError) generateAC()
                    digest.getPanFromAllData()
                    digest.getCardHolderNameFromAllData()
                    digest.assessFormFactor()
                    //digest.calcApduTimes()
                    isGood = !isIoError
                }
            } else isGood = false
            isoDep.close()
        } else isGood = false
        val tapEnd = Date().time // in msec
        digest.calcApduTimes()
        digest.summaryLog("\nCard read on ${getFormattedTodayDate()}")
        digest.summaryLog("Total session time: ${tapEnd-tapStart} ms.")
        val suffix =  if(digest.generateAcTransceiveMsec>0)" and ${digest.generateAcTransceiveMsec} ms for Generate AC."
                        else "."
        digest.summaryLog (
            "Total net IsoDep APDU transceive time: ${digest.totalTransieveMsec} ms.")
        digest.summaryLog (
                    "IsoDep APDU transceive time for GPO: ${digest.gpoTransieveMsec} ms" + suffix +
                    "\nTotal data received from card: ${digest.totalTransceiveBytes} bytes.")

        if (digest.transceiveSpeedKbitsPerSec > 0.1)
            digest.summaryLog ( "Effective transceive speed: " +
                                "${String.format("%.1f",digest.transceiveSpeedKbitsPerSec)} kbit/s.")

       Log.d(LOG_NFC_CARD,"explore() end. Return isGood=$isGood")
       return digest
    }

   // rstruct @Throws(IOException::class)
    private fun explorePPSE(): Int {
        // returns 0 if success
        Log.d(LOG_NFC_CARD,"explorePPSE() start")
        // rstruct isoDep.connect() // communication with tag adapter is established. Start talking with card
        val retrievedPPSE = executeApdu(EMV_SELECT_PPSE, "Select PPSE")

        digest.retrievedTags.add(retrievedPPSE)
        if (retrievedPPSE.isError) { // this also covers IO Error
            logBads(retrievedPPSE.errorDescription)
            return -1
        }
        /******
        EMV® Contactless Specifications for Payment Systems, Book B v2.10. Table 3-2
        6F FCI Template
            84 DF Name (‘2PAY.SYS.DDF01’)
            A5 FCI Proprietary Template
                BFOC FCI Issuer Discretionary Data
                    61 Directory Entry
        ******/

        val ppseEmvTag: EmvTag = retrievedPPSE.emvTag
        if (!ppseEmvTag.hasTagCode(EmvTag.CODE_FCI_TEMPLATE_6F)) {
            logBads(context.getString(R.string.select_ppse_not_6f))
            return - 2
        }
        val directoriesTag = ppseEmvTag.findTag(EmvTag.CODE_FCI_PROPR_A5).
            findTag(EmvTag.CODE_FCI_IDD_BF0C)
        if(!directoriesTag.hasTagCode(EmvTag.CODE_FCI_IDD_BF0C)) {
            logBads(context.getString(R.string.select_ppse_no_dirs))
            return -3
        }
        if(directoriesTag.myTags.size == 0) {
            logBads(context.getString(R.string.select_ppse_zero_dirs))
            return -4
        }
        if(parseDirectories(directoriesTag)<0 ) {
                return -5
        }
        Log.d(LOG_NFC_CARD,"explorePPSE() successful end")
        return 0
    }

    private fun parseDirectories (emvTag: EmvTag): Int {
        // explores each directory entry
        Log.d(LOG_NFC_CARD,"parseDirectories() start")
        if (emvTag.myTags.isEmpty()) { // cannot happen
            logBads("\nUnexpected Directory Content")
            return -1
        }
        for (dirEntry in emvTag.myTags) { // scan all dir entries
            if(dirEntry.hasTagCode(EmvTag.CODE_DIR_ENTRY_61)) {
                var appLabel = DATA_ABSENT
                var aidHex = DATA_ABSENT
                var kernelId = DATA_ABSENT
                //var cType = CardType.UNKNOWN
                var aid = byteArrayOf()
                for (d in dirEntry.myTags) { // scan all usefull elements in dir entry
                    if (d.hasTagCode(EmvTag.CODE_ADF_NAME_4F)) {
                        aidHex = d.elementaryEmvTagValueHex
                        aid = d.elementaryEmvTagValueBin
                        if (equalCodes(EmvTag.VISA_AID_PREF,aid))
                            cType = CardType.VISA
                        if (equalCodes(EmvTag.MASTERCARD_AID_PREF,aid))
                            cType = CardType.MASTERCARD
                        if (equalCodes(EmvTag.INTERAC_AID_PREF,aid))
                            cType = CardType.INTERAC
                        if (equalCodes(EmvTag.AMEX_AID_PREF,aid))
                            cType = CardType.AMEX
                        if (equalCodes(EmvTag.DINERS_AID_PREF,aid))
                            cType = CardType.DINERS
                        if (equalCodes(EmvTag.JCB_AID_PREF,aid))
                            cType = CardType.JCB
                        if (equalCodes(EmvTag.CUP_AID_PREF,aid))
                            cType = CardType.CUP
                        if (equalCodes(EmvTag.EFTPOS_AID_PREF,aid))
                            cType = CardType.EFTPOS // australia debit and credit
                    }

                    if (d.hasTagCode(EmvTag.CODE_APP_LABEL_50)) appLabel =
                        d.elementaryEmvTagValueString
                    if (d.hasTagCode(EmvTag.CODE_KERNEL_ID_9F2A)) kernelId =
                        d.elementaryEmvTagValueHex
                }
                //fake stuff, for muli-applicatin debug only. Adding extra card directory
                //digest.cardDirectories.add(EmvDirectory(aid, "aidHex","fake label",kernelId, cType))

                //real stuff
                digest.cardDirectories.add(EmvDirectory(aid, aidHex,appLabel,kernelId, cType))
            } else { // the tag is a directory entry
                /**** let's just ignoe it.
                logBads("\n" + context.getString(R.string.dirs_invalid_content))
                return -1
                *****/
            }
        }
        if (digest.cardDirectories.isEmpty()) { // no directories found
            logBads("\n" + context.getString(R.string.dirs_invalid_content))
            return -2
        }
        Log.d(LOG_NFC_CARD,"parseDirectories() normal end")
        return 0
    }


    private fun readAllData(retrievedAdf: RetrievedEmvTag) {
        // collects all data from the card
        // tries GPO first, if does not work, tries to read some known records/ files
        Log.d(LOG_NFC_CARD, "ReadAlldData() start")
        lateinit var afl: ByteArray
        val adfTag: EmvTag = retrievedAdf.emvTag

        var retrievedGpoResp: RetrievedEmvTag =
            if (buildPdol(adfTag))  // found PDOL
                executeGPO(pdol.termPdol)
            else // try GPO with zero value (good for Kernel 2 mastercard)
                executeGPO(byteArrayOf())

        if (retrievedGpoResp.isError) { //can be Io Error too
            if (!isIoError) tryAllRecords()
        } else { // GPO was good
            digest.retrievedTags.add(retrievedGpoResp)
            digest.gpoTransieveMsec = retrievedGpoResp.apduMsec
            val aflTag = retrievedGpoResp.emvTag.findTag(EmvTag.CODE_GPO_RESP_FORMAT2_77).
            findTag(EmvTag.CODE_AFL_94)
            if (aflTag.hasTagCode(EmvTag.CODE_AFL_94)) { // found AFL
                afl = aflTag.elementaryEmvTagValueBin
                readRecordsFromAFL(afl) // read all records specified in AFL
            } else { // cannot find afl - should never happen
                if (!isIoError) tryAllRecords()
            }
        }
        Log.d(LOG_NFC_CARD, "ReadAlldData() ends")
    }

    private fun equalCodes(a: ByteArray, b: ByteArray) : Boolean {
        // compares arrays, 1st one must be not longer.
        if (a.size>b.size) return false
        for (i in a.indices) {
            if (a[i]!=b[i]) return false
        }
        return true
    }

    private fun generateAC() {
        // if CDOL1 is found, execute Generate AC
        for (rTag in digest.retrievedTags) {
            if (!rTag.isError) {
                val cdolEmvTag = rTag.emvTag
                if (cdolEmvTag.cdol1Body.isNotEmpty()) {
                    val cdol = Pdol(cdolEmvTag.cdol1Body,Pdol.PDOL_TYPE_CDOL1, cType)
                    val termCdol1 = cdol.termPdol
                    val cmd = when(cType) {
                        CardType.INTERAC -> EMV_GENERATE_AC_INTERAC
                        else -> EMV_GENERATE_AC_GENERIC
                    }

                    val recAcApduReq = ByteArray(cmd.size+termCdol1.size+2) // + Lc and Le
                    for (i in cmd.indices) recAcApduReq[i] = cmd [i] // copy cmd
                    recAcApduReq [cmd.size] = termCdol1.size.toByte() // Lc - data length
                    if (termCdol1.isNotEmpty())
                        for (i in termCdol1.indices)
                            recAcApduReq[i+ cmd.size+1] = termCdol1[i] // copy PDOL
                    recAcApduReq [recAcApduReq.size - 1] = 0x00.toByte()
                    Log.d(
                        LOG_NFC_CARD,"Trying Generate AC APDU: ${toHex(recAcApduReq)}" +
                                " TERM CDOL1 length = ${termCdol1.size}, termCdol1 body = ${toHex(termCdol1)}" )

                    val retreivedGenerateAc = executeApdu(recAcApduReq, "Generate AC with CDA Flag")
                    if (retreivedGenerateAc.isError) {
                        // do nothing. Error is processed in eseceuteAPDU
                    } else { // Recovered AC is Good
                        digest.retrievedTags.add(retreivedGenerateAc)
                        digest.generateAcTransceiveMsec = retreivedGenerateAc.apduMsec
                    }
                    return // from the cycle  -we need Generate AC only once
                }
            }
       }
    }


    // rstruct @Throws(IOException::class)
    private fun selectAdf(adf: ByteArray): RetrievedEmvTag{
        Log.d(LOG_NFC_CARD,"selectAdf() start")
        // Create APDU request command
        val selectAdfCmd = ByteArray(SELECT_CMD_LENGTH +adf.size+2)
        for (i in 0 until SELECT_CMD_LENGTH) selectAdfCmd[i] = EMV_SELECT_PPSE[i] // command code
        selectAdfCmd [SELECT_CMD_LENGTH] = adf.size.toByte()// data length
        for (i in adf.indices) selectAdfCmd[SELECT_CMD_LENGTH + 1 + i] = adf[i]
        selectAdfCmd[selectAdfCmd.size - 1] = 0x00.toByte()
        val retrievedAdf = executeApdu(selectAdfCmd,"Select ADF")
        Log.d(LOG_NFC_CARD,"selectAdf Response:${retrievedAdf.emvTag}")
        if (retrievedAdf.isError) {
            logBads(retrievedAdf.errorDescription)
        }
        digest.retrievedTags.add(retrievedAdf)
        Log.d(LOG_NFC_CARD,"selectAdf() end")
        return retrievedAdf
    }

    // rstruct @Throws(IOException::class)
     private fun executeApdu(apdu: ByteArray, tittle: String, fakeData: String=""): RetrievedEmvTag {
        // fakeData is used for debug purposes only
        Log.d(LOG_NFC_CARD,"executeApdu() start. tittle=$tittle")
        val startMsec = Date().time // in msec
        var apduResponse= byteArrayOf()
        var errorDescription = ""
        if (fakeData.isEmpty()) {
            try { // rstruct
                apduResponse = isoDep.transceive(apdu)
                digest.rawApdus.add("\n$tittle: APDU REQUEST: ${toHex(apdu)}\nAPDU RESPONSE: ${toHex(apduResponse)}")
            } catch (e: IOException) {
                Log.e(LOG_NFC_CARD, "IO Error")
                isIoError = true
                errorDescription =
                    "I/O Error in APDU $tittle. IsoDep error message: " + (e.message ?: "")
                digest.rawApdus.add("\n$tittle:APDU REQUEST: ${toHex(apdu)}\n$errorDescription")
            }
        } else { // use debud data instead of reading the card
            apduResponse = converToBytes(fakeData)
            digest.rawApdus.add("\n$tittle: APDU REQUEST: ${toHex(apdu)}\nAPDU RESPONSE: ${toHex(apduResponse)}")
        }
        val apduMsec = Date().time-startMsec
        // rstruct digest.rawApdus.add("\nAPDU REQUEST: ${toHex(apdu)}\n$tittle: ${toHex(apduResponse)}")
        //if (apduResponse==null) apduResponse=byteArrayOf() // just in case, should not happen
        Log.d(LOG_NFC_CARD,"APDU Response: ${toHex(apduResponse)}")
        val sws: ByteArray =
            if (apduResponse.size < 2)  byteArrayOf()
            else ByteArray(2) { i -> apduResponse[apduResponse.size-2+i]}

        //val end = apduResponse.size-1
        //val sws=ByteArray(2) { i -> apduResponse[end-1+i]}
        val swsHex = if (sws.isEmpty()) "none" else toHex(sws)
        val swIsGood = if (sws.isEmpty()) false else equalCodes(GOOD_SW,sws)
        //rstruct var errorDescription = ""
        var isError = true
        lateinit var emvTag: EmvTag // = EmvTag(byteArrayOf(),0,0,-1) //fictitious EmvTag
        if ((!swIsGood) or (apduResponse.size < 8) or isIoError) {
            if (!isIoError)
                errorDescription = "Command $tittle - APDU Response is too short. sws=$swsHex."
            // else - errosescription already filled in in IOError catch
            logAbnormals(errorDescription)
            emvTag = EmvTag( byteArrayOf(), 0, 100,
                -1, context) // creating dummy EMV Tag
            emvTag.emvTagError = errorDescription
            emvTag.isSourceDataError = true
        } else {
            emvTag = EmvTag(apduResponse, 0,
                apduResponse.size-2, // minus sws
                0, context)
            if (emvTag.isSourceDataError) {
                errorDescription = "Command $tittle - APDU Response is not an EMV Tag"
                logAbnormals(errorDescription)
            } else isError=isIoError // should be false but just in case
        }

        val retrievedTag =
             RetrievedEmvTag(tittle + " APDU", apduResponse, swsHex, emvTag, isError, errorDescription, apduMsec, apdu)
        Log.d(LOG_NFC_CARD,"executeApdu() end")
        return retrievedTag
    }

    private fun converToBytes(fake: String) :ByteArray {
        // fir debug purpses. Inteprets string's each couple of symbols  as a hex code of bytes
        val ret = ByteArray(fake.length/2)
        var fakeInd = 0
        for (i in ret.indices) {
            var b: Byte = 0
            b = try {
                val c1:Int = fake[fakeInd].digitToInt(radix = 16)
                val c2:Int = fake[fakeInd + 1].digitToInt(radix = 16)
                val n:Int = (c1*16+c2)
                n.toByte()

            } catch (e: Exception) {
                0
            }
            ret[i] = b
            fakeInd+=2
            if (fakeInd >= fake.length) break
        }
        return ret
    }

    private fun executeGPO(termPdol: ByteArray): RetrievedEmvTag {
        val gpoApduReq = prepareGPOCommand(termPdol)
        Log.d(
            LOG_NFC_CARD, "Trying GPO with PDOL. GPO APDU: ${toHex(gpoApduReq)}" +
                    " Term PDOL length = ${termPdol.size}, termPDol body = ${toHex(termPdol)}"
        )
        return executeApdu(gpoApduReq, "GPO")
    }

    private fun prepareGPOCommand(termPdol: ByteArray): ByteArray {
        val gpoApduReq = ByteArray(EMV_GPO_CMD.size+termPdol.size+2) // + Lc and Le
        for (i in EMV_GPO_CMD.indices) gpoApduReq[i] = EMV_GPO_CMD[i] // copy cmd
        gpoApduReq [EMV_GPO_CMD.size] = termPdol.size.toByte() // Lc - data length
        if (termPdol.isNotEmpty())
            for (i in termPdol.indices) gpoApduReq[i+ EMV_GPO_CMD.size+1] = termPdol[i] // copy PDOL
        gpoApduReq [gpoApduReq.size - 1] = 0x00.toByte()
        return gpoApduReq
    }



    @Throws(IOException::class)
    private fun readRecord(rec: Int, file: Int): RetrievedEmvTag {
        Log.d(LOG_NFC_CARD, "readRecord() start. rec=$rec. file=$file")
        val readRecAPDU = hexBytes(
            0x00, 0xB2,
            0x00/* [2] - record # */,
            0x04/* [3] file # in 5 left bits. 3rd bit must be 1 */,
            0x00
        )
        readRecAPDU[2] = rec.toByte()
        val intFile: Int = ((file*8) and 0xF8) + 4
        readRecAPDU[3] = intFile.toByte()
        val retrievedTag=executeApdu(readRecAPDU, "Read Record $rec in file $file")
        Log.d(LOG_NFC_CARD,"readRecord() end. ${retrievedTag.emvTag.toString()}")
        return retrievedTag
    }

    private fun readRecordsFromAFL (afl: ByteArray) {
        Log.d(LOG_NFC_CARD, "readRecordFromAFL() start")
        // AFL consits of 4-byte chunks
        // first Byte of each chunk has File number (SFI) in first 5 bits)
        Log.d(LOG_NFC_CARD, "readRecordFromAFL: AFL Tag:\n${toHex(afl)}")

        var chunkStart=0
        while(chunkStart < afl.size-3) {
            val fileNumber = afl[chunkStart].toUByte().toInt() / 8
            val firstRecNumber = afl[chunkStart+1].toUByte().toInt()
            val lastRecNumber = afl[chunkStart+2].toUByte().toInt()
            Log.d(
                LOG_NFC_CARD,
                    "\nfileNumber=$fileNumber, firstRecNumber=$firstRecNumber, lastRecNumber=$lastRecNumber")
            if (firstRecNumber <= lastRecNumber)
                for (rec in firstRecNumber..lastRecNumber) {
                    val retrievedRecord = readRecord(rec, fileNumber)
                    if(!retrievedRecord.isError)
                        digest.retrievedTags.add(retrievedRecord)
                }
            chunkStart+=4
        }

        Log.d(LOG_NFC_CARD, "readRecordFromAFL() ends")
    }

    private fun tryAllRecords () {
        // reafs record until PAN is found
        Log.d(LOG_NFC_CARD, "readAllRecors() start")
        var enough = false
        for (file in 1..2)
            if (!enough) {
                for (rec in 1..5) {
                    if (!enough) {
                        val retrievedRecord = readRecord(rec, file)
                        if (!retrievedRecord.isError)
                            digest.retrievedTags.add(retrievedRecord)
                        enough = retrievedRecord.emvTag.hasPans or isIoError
                    }
                }
            }
        if (!enough) {
            for (file in 3..5)
                if (!enough) {
                    for (rec in 1..5) {
                        if (!enough) {
                            val retrievedRecord = readRecord(rec, file)
                            if (!retrievedRecord.isError)
                                digest.retrievedTags.add(retrievedRecord)
                            enough = retrievedRecord.emvTag.hasPans or isIoError
                        }
                    }
                }
        }
        Log.d(LOG_NFC_CARD, "readAllRecors() ends")
    }

    private fun buildPdol(adfTag: EmvTag ): Boolean {
        // reterns true if we can try GPO
        return if(adfTag.pdolBody.isNotEmpty()) { // must be, just in case
            Log.d(LOG_NFC_CARD,"Found PDOL Tag, length=${adfTag.pdolBody.size}")
            // get constructed Terminal PDOL
            pdol = Pdol(adfTag.pdolBody, Pdol.PDOL_TYPE_PDOL, cType)
            if (pdol.cardPdolBody.isNotEmpty())  // we have good pdol
                true
            else {
                pdol = Pdol(byteArrayOf(), Pdol.PDOL_TYPE_PDOL,cType) // create dummy pdol 8300. Should never happen
                true
            }
        } else { // no card PDOL
            pdol = Pdol(byteArrayOf(), Pdol.PDOL_TYPE_PDOL,cType) // create dummy pdol 8300
            true
        }
    }

    private fun logAbnormals(error: String) {
        digest.abnormalLog(error)
        Log.d(LOG_NFC_CARD, error)
    }

    private fun logBads(error: String) {
        digest.badThingsLog(error)
        Log.e(LOG_NFC_CARD, error)
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        val EMV_SELECT_PPSE = hexBytes(
            0x00, 0xA4, 0x04, 0x00, 0x0E,
            0x32, 0x50, 0x41, 0x59, 0x2E,  //"2PAY."
            0x53, 0x59, 0x53, 0x2E,         //"SYS."
            0x44, 0x44, 0x46, 0x30, 0x31,   //"DDF01"
            0x00
        )

        val INTERAC_AFL    = hexBytes(0x10, 0x01, 0x02, 0x00) // file 2
        val MASTERCARD_AFL = hexBytes(0x08, 0x01, 0x01, 0x00) // file 1
        val VISA_AFL       = hexBytes(0x08, 0x01, 0x02, 0x00) // not as in GPO: 10 02 04

        // we do not know these, assume
        val AMEX_AFL       = hexBytes(0x10, 0x02, 0x04, 0x00)
        val DISCOVER_AFL   = hexBytes(0x10, 0x02, 0x04, 0x00)
        val JCB_AFL        = hexBytes(0x10, 0x02, 0x04, 0x00)
        val DINERS_AFL     = hexBytes(0x10, 0x02, 0x04, 0x00)
        val CUP_AFL        = hexBytes(0x10, 0x02, 0x04, 0x00) // China Union Pay
        val OTHER_AFL      = hexBytes(0x10, 0x02, 0x04, 0x00)


        val EMV_GPO_CMD           = hexBytes(0x80, 0xA8, 0x00, 0x00) // CLA, INS, P1, P2
        val EMV_GENERATE_AC_GENERIC = hexBytes(0x80, 0xAE, 0x90, 0x00) // CLA, INS, P1, P2 flags = ARQC + CDA
        val EMV_GENERATE_AC_INTERAC = hexBytes(0x80, 0xAE,
            //0x80, 0x00) // CLA, INS, P1, P2 flags = ARQC
            0x90, 0x00) // CLA, INS, P1, P2 flags = ARQC + CDA

        const val LOG_NFC_CARD = "NFC_CARD"
        const val SELECT_CMD_LENGTH = 4
        val GOOD_SW = hexBytes(0x90, 0x00)
        const val DATA_ABSENT = "absent"

        fun getFormattedTodayDate(): String {
            val cal = Calendar.getInstance()
            val dat = IntArray(3)
            dat[0] = cal.get(Calendar.YEAR)
            dat[1] = cal.get(Calendar.MONTH)+1
            dat[2] = cal.get(Calendar.DAY_OF_MONTH)

            val tim = IntArray(3)
            tim[0] = cal.get(Calendar.HOUR_OF_DAY)
            tim[1] = cal.get(Calendar.MINUTE)
            tim[2] = cal.get(Calendar.SECOND)

            return dat.joinToString("/") { "%02d".format(it)} + " at " + tim.joinToString(":") { "%02d".format(it)}
        }
    } // conpanion object
}