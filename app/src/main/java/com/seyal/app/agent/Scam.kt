package com.seyal.app.agent

/**
 * Spots the common scam messages before someone taps the link or calls the number: fake KYC and
 * electricity-disconnection notices, prizes, "share the OTP", parcel fees, job offers, fake police.
 * Rules, not AI: it runs on the phone on every incoming message, costs nothing, and nothing leaves
 * the device. It errs towards silence: a real bank alert or OTP must never be called a scam.
 */
object Scam {
    class Verdict(val score: Int, val reasons: List<String>) {
        /** One plain sentence for the warning. */
        val why: String get() = reasons.first()
    }

    /** Scores at or above this are warned about. */
    const val WARN = 4

    private val url = Regex("(?:https?://|www\\.)[^\\s<>\"')]+|\\b[a-z0-9-]+\\.(?:xyz|top|online|site|icu|click|buzz|vip|live|shop|info|cc|in|com|co|net|org|apk)(?:/[^\\s]*)?", RegexOption.IGNORE_CASE)
    private val shortLink = Regex("\\b(bit\\.ly|tinyurl\\.com|cutt\\.ly|t\\.ly|rb\\.gy|is\\.gd|tiny\\.cc|shorturl\\.at|ow\\.ly|rebrand\\.ly|bitly\\.ws|goo\\.su|s\\.id|t\\.co|linktr\\.ee|wa\\.link)\\b", RegexOption.IGNORE_CASE)
    private val oddDomain = Regex("\\.(xyz|top|online|site|icu|click|buzz|vip|live|cc|apk)(/|\\b)|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|-(kyc|update|verify|secure|reward|bank|pan|login)[.-]|(kyc|update|verify|reward|refund)-", RegexOption.IGNORE_CASE)
    private val apk = Regex("\\.apk\\b", RegexOption.IGNORE_CASE)
    /** Sites that are what they say they are: a link to one is no warning sign. */
    private val official = Regex(
        "\\b(sbi\\.co\\.in|onlinesbi\\.sbi|hdfcbank\\.com|icicibank\\.com|axisbank\\.com|kotak\\.com|canarabank\\.com|indianbank\\.in|iob\\.in|unionbankofindia\\.co\\.in|pnbindia\\.in|bankofbaroda\\.in|" +
            "tnebnet\\.org|tangedco\\.org|tnpdcl\\.org|gov\\.in|nic\\.in|npci\\.org\\.in|uidai\\.gov\\.in|incometax\\.gov\\.in|irctc\\.co\\.in|amazon\\.in|amzn\\.in|amzn\\.to|flipkart\\.com|fkrt\\.it|dl\\.flipkart\\.com|myntra\\.com|meesho\\.com|" +
            "jio\\.com|airtel\\.in|myvi\\.in|bsnl\\.co\\.in|paytm\\.com|p\\.paytm\\.me|phonepe\\.com|phon\\.pe|google\\.com|goo\\.gl|g\\.co|youtube\\.com|youtu\\.be|swiggy\\.com|zomato\\.com|zoma\\.to|bookmyshow\\.com|redbus\\.in|" +
            "forms\\.gle|docs\\.google\\.com|drive\\.google\\.com|meet\\.google\\.com|zoom\\.us|linkedin\\.com|lnkd\\.in|github\\.com|whatsapp\\.com|instagram\\.com|facebook\\.com|delhivery\\.com|bluedart\\.com|ekartlogistics\\.com|dtdc\\.in|indiapost\\.gov\\.in)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val phoneInText = Regex("(?<!\\d)(?:\\+?91[\\s-]?)?[6-9]\\d{4}[\\s-]?\\d{5}(?!\\d)")
    /** A sender shown as a bare mobile number: banks and boards write from sender IDs, not from someone's phone. */
    private val personalNumber = Regex("^\\+?(?:91)?[\\s-]?[6-9]\\d{4}[\\s-]?\\d{5}$")
    /** A registered sender ID: "AX-HDFCBK", "VM-TNEBLT", "JD-SBIUPI-S". */
    private val senderId = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$")

    private val asksSecret = Regex("\\b(share|send|tell|give|provide|forward|enter|confirm)\\b.{0,30}\\b(otp|pin|cvv|password|passcode|card (number|details)|verification code)\\b|\\b(otp|pin|cvv)\\b.{0,20}\\b(share|send|tell|batao|sollunga|kodu)\\b", RegexOption.IGNORE_CASE)
    private val ownOtp = Regex("\\b(otp|one[- ]time password|verification code)\\b.{0,40}\\b(is|:)\\s*\\d{4,8}|\\d{4,8}\\s+is (your|the) (otp|one[- ]time|verification)|do not share|don'?t share|never share", RegexOption.IGNORE_CASE)
    private val remoteApp = Regex("\\b(anydesk|any desk|teamviewer|team viewer|quicksupport|quick support|rustdesk|screen ?shar(e|ing)|remote access|airdroid)\\b", RegexOption.IGNORE_CASE)
    private val threat = Regex("\\b(will be|has been|is|been|get|gets)\\s+(blocked|suspended|deactivated|disconnected|frozen|closed|terminated|restricted|on hold)\\b|\\b(within 24 ?(hours|hrs)|immediately|tonight|today itself|last (warning|chance|reminder)|final (notice|warning)|legal action|arrest(ed)?|fir\\b|court|penalty|expire[sd]? today|avoid (block|suspension|disconnection))", RegexOption.IGNORE_CASE)
    private val kyc = Regex("\\b(e-?kyc|kyc|pan( card)?|aadhaa?r|re-?kyc)\\b.{0,60}\\b(updat\\w*|verif\\w*|expir\\w*|suspend\\w*|block\\w*|link\\w*|pending|incomplete|complete)\\b|\\b(updat\\w*|verif\\w*|complete)\\b.{0,30}\\b(e-?kyc|kyc|pan( card)?|aadhaa?r)\\b", RegexOption.IGNORE_CASE)
    private val power = Regex("\\b(electricity|power|current|eb|bijli|light bill|electric)\\b.{0,80}\\b(disconnect\\w*|cut( off)?|cutoff)\\b|\\b(disconnect\\w*)\\b.{0,60}\\b(electricity|power|eb)\\b|மின்சாரம்.{0,40}துண்டிக்க", RegexOption.IGNORE_CASE)
    private val callUs = Regex("\\b(call|contact|whatsapp|dial|reach)\\b", RegexOption.IGNORE_CASE)
    private val prize = Regex("\\b(you (have |'ve )?won|winner|lottery|lucky draw|jackpot|kbc|prize|gift (card|voucher) worth|selected for (a )?(reward|prize|gift)|congratulations!? you)\\b", RegexOption.IGNORE_CASE)
    private val claim = Regex("\\b(claim|collect|redeem|lakhs?|crores?|₹|rs\\.?|inr|iphone|car|bike)\\b", RegexOption.IGNORE_CASE)
    /** A promise of easy daily money: a warning sign whoever sends it. */
    private val jobEasy = Regex("\\b(earn (upto |up to )?(₹|rs\\.?|inr)? ?\\d[\\d,]*.{0,20}(daily|per day|a day|/day|weekly|every day)|daily (income|earning|payment|salary)|(₹|rs\\.?) ?\\d[\\d,]*.{0,12}(per day|daily)|telegram task|like (youtube )?videos|review (hotels|products|google).{0,20}(earn|paid|pay)|no (investment|experience) (needed|required))\\b", RegexOption.IGNORE_CASE)
    /** Ordinary job words: only suspicious from a stranger's number or with a chat link. */
    private val jobSoft = Regex("\\b(part[- ]?time (job|work)|work from home|hr (manager|from|team)|hiring (now|urgently)|job offer)\\b", RegexOption.IGNORE_CASE)
    private val chatLink = Regex("\\b(wa\\.me|wa\\.link|t\\.me|telegram)\\b", RegexOption.IGNORE_CASE)
    private val loan = Regex("\\b(loan (of |amount )?.{0,25}(approved|sanctioned|disbursed|credited)|pre-?approved (personal )?loan|instant (personal )?loan|loan without (documents|cibil))\\b", RegexOption.IGNORE_CASE)
    private val parcel = Regex("\\b(parcel|courier|package|shipment|consignment|customs|india post|speed post)\\b.{0,80}\\b(held|on hold|pending|undelivered|incomplete address|wrong address|address (is )?(incomplete|incorrect|missing)|pay.{0,20}(fee|charge|duty)|re-?delivery|returned|could not be delivered|unable to deliver)\\b", RegexOption.IGNORE_CASE)
    private val digitalArrest = Regex("\\bdigital(ly)? arrest(ed)?\\b", RegexOption.IGNORE_CASE)
    private val authorityName = Regex("\\b(cbi|crime branch|narcotics|ncb|cyber (cell|police|crime)|enforcement directorate|ed officer|trai|telecom (department|authority)|income tax (department|officer)|customs (officer|department)|police (officer|station|department)|inspector|mumbai police|delhi police)\\b", RegexOption.IGNORE_CASE)
    private val authorityClaim = Regex("\\b(money laundering|arrest warrant|under (arrest|investigation)|(case|fir|complaint) (has been |is |was )?(registered|filed|lodged) against you|illegal (parcel|activit\\w+|transactions?|items?|drugs?)|drugs? (were |was )?found|your (aadhaar|aadhar|pan|number|sim|mobile number|parcel|bank account) (card )?(is|was|has been) (used|linked|misused|involved|seized)|sim (card )?(will be |is |has been )?(blocked|deactivated|suspended)|legal action|stay on (the )?(video )?call)\\b", RegexOption.IGNORE_CASE)
    private val refund = Regex("\\b(refund|cashback|reward points?|bonus points?|credit card points?|loyalty points?)\\b.{0,60}\\b(expir\\w*|claim|redeem|today|click|process\\w*|pending)\\b", RegexOption.IGNORE_CASE)
    private val newNumber = Regex("\\b(this is my new (number|no)|my new (number|no)\\b|lost my phone|my phone (is|got) (broken|lost|stolen|damaged)|changed my number)", RegexOption.IGNORE_CASE)
    private val asksMoney = Regex("\\b(urgent(ly)? (need|send|want).{0,20}(money|₹|rs|cash|help)|need (some )?money|send (me )?(some )?(money|₹|rs\\.? ?\\d)|(gpay|g pay|phonepe|paytm|transfer|upi) (me |pannu|panna|pannunga)?.{0,12}\\d{3,}|can you (gpay|send|transfer|pay).{0,20}(\\d{3,}|money)|will return (it )?(tomorrow|soon|by)|\\d{3,} (anuppu|podu|pannu))", RegexOption.IGNORE_CASE)
    private val hardship = Regex("\\b(hospital|accident|emergency|stuck|stranded|police station|operation|surgery)\\b", RegexOption.IGNORE_CASE)
    private val bankWords = Regex("\\b(bank|account|a/c|debit card|credit card|atm|net ?banking|upi|sbi|hdfc|icici|axis|kotak|canara|pnb|yono)\\b", RegexOption.IGNORE_CASE)
    private val dearCustomer = Regex("\\bdear (customer|user|consumer|sir|client|account holder|sbi user|member)\\b", RegexOption.IGNORE_CASE)

    /**
     * Looks at one incoming message. [sender]: the notification's title (a name, a number or a sender ID).
     * Returns null when nothing about it looks like a scam.
     */
    fun check(sender: String, text: String): Verdict? {
        val t = text.trim()
        if (t.length < 20) return null
        val who = sender.trim()
        // A real OTP message says "do not share": only worth a warning if it then asks for the code anyway.
        val isOwnOtp = ownOtp.containsMatchIn(t)
        val links = (url.findAll(t).map { it.value } + shortLink.findAll(t).map { it.value } + chatLink.findAll(t).map { it.value }.filter { it.contains('.') }).distinct().toList()
        val badLinks = links.filterNot { official.containsMatchIn(it) }
        val fromPerson = personalNumber.matches(who.replace(" ", ""))
        val fromSenderId = senderId.matches(who.uppercase()) || senderId.matches(who)
        val numberInText = phoneInText.containsMatchIn(t)

        var score = 0
        val reasons = mutableListOf<String>()
        fun add(points: Int, reason: String) { score += points; reasons += reason }

        // The code for a delivery or a ride is meant to be told to the person at the door.
        val handover = Regex("\\b(deliver\\w*|parcel|package|order|courier|rider|driver|ride|trip|cab|auto|pick ?up|gate|security)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) && badLinks.isEmpty() && !bankWords.containsMatchIn(t)
        if (!handover && asksSecret.containsMatchIn(t) && !(isOwnOtp && !Regex("\\b(share|send|tell|give|provide|forward)\\b.{0,25}\\b(otp|pin|cvv|code)\\b.{0,40}\\b(to|with) (us|me|our|the (agent|officer|executive))", RegexOption.IGNORE_CASE).containsMatchIn(t)))
            add(4, "It asks for an OTP, PIN or card details. No bank, shop or office ever asks for these.")
        if (remoteApp.containsMatchIn(t)) add(4, "It asks you to install a screen-sharing app. That lets a stranger control your phone and bank apps.")
        if (apk.containsMatchIn(t)) add(4, "It sends an app file (.apk) to install. Real companies only use the Play Store.")
        if (power.containsMatchIn(t) && (numberInText || badLinks.isNotEmpty() || fromPerson) && callUs.containsMatchIn(t) || (power.containsMatchIn(t) && fromPerson))
            add(4, "It threatens to cut your electricity and gives a private number or link. The electricity board does not do this by message.")
        val police = "It claims to be the police, CBI, customs or TRAI. They never contact people this way, and \"digital arrest\" does not exist."
        when {
            fromSenderId -> {}
            digitalArrest.containsMatchIn(t) -> add(4, police)
            authorityName.containsMatchIn(t) && authorityClaim.containsMatchIn(t) -> add(4, police)
            authorityClaim.containsMatchIn(t) && !Regex("^[\\p{L}]").containsMatchIn(who) -> add(3, police)
        }
        if (prize.containsMatchIn(t) && claim.containsMatchIn(t)) add(3, "It says you won a prize. You can't win a draw you never entered.")
        if (jobEasy.containsMatchIn(t) || (jobSoft.containsMatchIn(t) && (fromPerson || chatLink.containsMatchIn(t) || shortLink.containsMatchIn(t))))
            add(3, "It offers easy money for simple work. These start by paying a little, then ask you to deposit.")
        if (parcel.containsMatchIn(t) && (badLinks.isNotEmpty() || numberInText)) add(3, "It says a parcel is stuck and wants a fee or your details through a link.")
        if (kyc.containsMatchIn(t) && (badLinks.isNotEmpty() || numberInText || fromPerson)) add(3, "It asks you to update KYC, PAN or Aadhaar through a link or a private number. Banks do this only in the branch or their own app.")
        // "This is my new number" plus a request for money, from a number that is not saved: the classic.
        val pretend = "It asks for money from a number you have not saved, possibly pretending to be someone you know. Call that person on their usual number first."
        if (fromPerson && asksMoney.containsMatchIn(t)) {
            if (newNumber.containsMatchIn(t)) add(4, pretend) else if (hardship.containsMatchIn(t)) add(3, pretend)
        }
        if (loan.containsMatchIn(t) && (badLinks.isNotEmpty() || fromPerson)) add(2, "It offers a loan you did not apply for.")
        if (refund.containsMatchIn(t) && badLinks.isNotEmpty()) add(2, "It pushes you to claim a refund or reward through a link.")
        if (threat.containsMatchIn(t) && (bankWords.containsMatchIn(t) || kyc.containsMatchIn(t)) && (badLinks.isNotEmpty() || numberInText || fromPerson))
            add(2, "It threatens to block your account unless you act now. Pressure like this is how scams work.")

        // What makes a suspicious message more so; these never warn on their own.
        if (score > 0) {
            if (badLinks.any { shortLink.containsMatchIn(it) || oddDomain.containsMatchIn(it) }) score += 2 else if (badLinks.isNotEmpty()) score += 1
            // A stranger's mobile number speaking as a bank, an office or a company.
            if (fromPerson) score += 1
            if (fromPerson && dearCustomer.containsMatchIn(t)) score += 1
            if (numberInText && callUs.containsMatchIn(t) && !fromSenderId) score += 1
        }
        // A registered sender with no link and no number to call is almost always the real thing.
        if (fromSenderId && links.isEmpty() && !numberInText) score -= 3
        if (fromSenderId && badLinks.isEmpty() && links.isNotEmpty()) score -= 2
        return if (score >= WARN && reasons.isNotEmpty()) Verdict(score, reasons) else null
    }

    /** The warning read aloud in simple mode. */
    fun spoken(tamil: Boolean): String =
        if (tamil) "கவனம். இப்போது வந்த மெசேஜ் மோசடியாக இருக்கலாம். அதில் உள்ள லிங்கை தொடாதீர்கள். அந்த எண்ணுக்கு கால் செய்யாதீர்கள். OTP யாருக்கும் சொல்லாதீர்கள்."
        else "Careful. The message that just came looks like a scam. Do not tap its link, do not call its number, and do not share any code."

    /** Senders the user said are fine ("Not a scam"). */
    fun trusted(sender: String, list: String): Boolean = list.split('\n').any { it.isNotBlank() && it.equals(sender.trim(), true) }
}
