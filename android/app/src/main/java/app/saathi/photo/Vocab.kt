package app.saathi.photo

/**
 * One kind of thing the photo description can name, in English and Hindi.
 * [key] merges look-alike classes from the two models ("dining table", "Desk", "Coffee table" → table),
 * so one object seen by both models is said once.
 */
data class Thing(
    val key: String,
    val en: String,
    val enPlural: String,
    val hi: String,
    val hiPlural: String,
)

/** A model class name mapped to a [Thing], with the lowest score that counts. */
data class VocabEntry(val thing: Thing, val minScore: Double)

/**
 * The classes photo description speaks about, from the two walk-mode YOLOv8n models
 * (walk/models: COCO, 80 classes, lowercase names; Open Images V7, 601 classes, Capitalised names).
 * Pure Kotlin, no Android, unit-tested on the JVM.
 *
 * Left out on purpose: classes that fire wrongly a lot on a phone photo (tie, frisbee, skis…), animals
 * that are almost always a misfire here (zebra, giraffe, bear), and every "human body part / man /
 * woman / clothing" class (the COCO "person" count is the reliable one; nobody is identified).
 */
object Vocab {
    /** COCO's scores run a little higher than Open Images' for the same quality; each gets its own floor. */
    const val COCO_MIN = 0.40
    const val OIV7_MIN = 0.45

    private val things = HashMap<String, Thing>()

    private fun t(key: String, en: String, enPl: String, hi: String, hiPl: String = hi): Thing =
        Thing(key, en, enPl, hi, hiPl).also { things[key] = it }

    // ---- the things (English singular/plural, Hindi singular/plural) --------------------------
    private val PERSON = t("person", "person", "people", "व्यक्ति", "लोग")
    private val BICYCLE = t("bicycle", "bicycle", "bicycles", "साइकिल", "साइकिलें")
    private val CAR = t("car", "car", "cars", "कार", "कारें")
    private val MOTORBIKE = t("motorbike", "motorbike", "motorbikes", "बाइक")
    private val BUS = t("bus", "bus", "buses", "बस", "बसें")
    private val TRUCK = t("truck", "truck", "trucks", "ट्रक")
    private val VAN = t("van", "van", "vans", "वैन")
    private val TRAIN = t("train", "train", "trains", "ट्रेन", "ट्रेनें")
    private val BOAT = t("boat", "boat", "boats", "नाव", "नावें")
    private val PLANE = t("plane", "plane", "planes", "हवाई जहाज़")
    private val TRAFFIC_LIGHT = t("traffic light", "traffic light", "traffic lights", "ट्रैफ़िक लाइट", "ट्रैफ़िक लाइटें")
    private val SIGN = t("sign", "road sign", "road signs", "साइन बोर्ड")
    private val HYDRANT = t("hydrant", "fire hydrant", "fire hydrants", "फ़ायर हाइड्रेंट")
    private val POLE = t("pole", "street light", "street lights", "बिजली का खंभा", "बिजली के खंभे")
    private val BENCH = t("bench", "bench", "benches", "बेंच", "बेंचें")
    private val TREE = t("tree", "tree", "trees", "पेड़")
    private val BUILDING = t("building", "building", "buildings", "इमारत", "इमारतें")
    private val BIRD = t("bird", "bird", "birds", "चिड़िया", "चिड़ियाँ")
    private val CAT = t("cat", "cat", "cats", "बिल्ली", "बिल्लियाँ")
    private val DOG = t("dog", "dog", "dogs", "कुत्ता", "कुत्ते")
    private val HORSE = t("horse", "horse", "horses", "घोड़ा", "घोड़े")
    private val SHEEP = t("sheep", "sheep", "sheep", "भेड़", "भेड़ें")
    private val COW = t("cow", "cow", "cows", "गाय", "गायें")
    private val GOAT = t("goat", "goat", "goats", "बकरी", "बकरियाँ")
    private val MONKEY = t("monkey", "monkey", "monkeys", "बंदर")
    private val ELEPHANT = t("elephant", "elephant", "elephants", "हाथी")
    private val BAG = t("bag", "bag", "bags", "बैग")
    private val SUITCASE = t("suitcase", "suitcase", "suitcases", "सूटकेस")
    private val UMBRELLA = t("umbrella", "umbrella", "umbrellas", "छाता", "छाते")
    private val BALL = t("ball", "ball", "balls", "गेंद", "गेंदें")
    private val KITE = t("kite", "kite", "kites", "पतंग", "पतंगें")
    private val BAT = t("bat", "bat", "bats", "बल्ला", "बल्ले")
    private val RACKET = t("racket", "racket", "rackets", "रैकेट")
    private val SKATEBOARD = t("skateboard", "skateboard", "skateboards", "स्केटबोर्ड")
    private val BOTTLE = t("bottle", "bottle", "bottles", "बोतल", "बोतलें")
    private val GLASS = t("glass", "glass", "glasses", "गिलास")
    private val CUP = t("cup", "cup", "cups", "कप")
    private val FORK = t("fork", "fork", "forks", "काँटा", "काँटे")
    private val KNIFE = t("knife", "knife", "knives", "चाकू")
    private val SPOON = t("spoon", "spoon", "spoons", "चम्मच")
    private val BOWL = t("bowl", "bowl", "bowls", "कटोरी", "कटोरियाँ")
    private val PLATE = t("plate", "plate", "plates", "प्लेट", "प्लेटें")
    private val JUG = t("jug", "jug", "jugs", "जग")
    private val KETTLE = t("kettle", "kettle", "kettles", "केतली", "केतलियाँ")
    private val COOKER = t("cooker", "pressure cooker", "pressure cookers", "प्रेशर कुकर")
    private val STOVE = t("stove", "gas stove", "gas stoves", "गैस चूल्हा", "गैस चूल्हे")
    private val BANANA = t("banana", "banana", "bananas", "केला", "केले")
    private val APPLE = t("apple", "apple", "apples", "सेब")
    private val ORANGE = t("orange", "orange", "oranges", "संतरा", "संतरे")
    private val CARROT = t("carrot", "carrot", "carrots", "गाजर")
    private val BROCCOLI = t("broccoli", "head of broccoli", "heads of broccoli", "ब्रोकली")
    private val TOMATO = t("tomato", "tomato", "tomatoes", "टमाटर")
    private val POTATO = t("potato", "potato", "potatoes", "आलू")
    private val EGG = t("egg", "egg", "eggs", "अंडा", "अंडे")
    private val BREAD = t("bread", "loaf of bread", "loaves of bread", "ब्रेड")
    private val SANDWICH = t("sandwich", "sandwich", "sandwiches", "सैंडविच")
    private val PIZZA = t("pizza", "pizza", "pizzas", "पिज़्ज़ा")
    private val DOUGHNUT = t("doughnut", "doughnut", "doughnuts", "डोनट")
    private val CAKE = t("cake", "cake", "cakes", "केक")
    private val HOT_DOG = t("hot dog", "hot dog", "hot dogs", "हॉट डॉग")
    private val CHAIR = t("chair", "chair", "chairs", "कुर्सी", "कुर्सियाँ")
    private val STOOL = t("stool", "stool", "stools", "स्टूल")
    private val SOFA = t("sofa", "sofa", "sofas", "सोफ़ा", "सोफ़े")
    private val BED = t("bed", "bed", "beds", "बिस्तर")
    private val TABLE = t("table", "table", "tables", "टेबल")
    private val CUPBOARD = t("cupboard", "cupboard", "cupboards", "अलमारी", "अलमारियाँ")
    private val SHELF = t("shelf", "shelf", "shelves", "शेल्फ़")
    private val PLANT = t("plant", "plant", "plants", "पौधा", "पौधे")
    private val FLOWER = t("flower", "flower", "flowers", "फूल")
    private val VASE = t("vase", "vase", "vases", "फूलदान")
    private val DOOR = t("door", "door", "doors", "दरवाज़ा", "दरवाज़े")
    private val WINDOW = t("window", "window", "windows", "खिड़की", "खिड़कियाँ")
    private val CURTAIN = t("curtain", "curtain", "curtains", "पर्दा", "पर्दे")
    private val STAIRS = t("stairs", "staircase", "staircases", "सीढ़ियाँ")
    private val LADDER = t("ladder", "ladder", "ladders", "लैडर")
    private val LAMP = t("lamp", "lamp", "lamps", "लैंप")
    private val CEILING_FAN = t("ceiling fan", "ceiling fan", "ceiling fans", "छत का पंखा", "छत के पंखे")
    private val FAN = t("fan", "fan", "fans", "पंखा", "पंखे")
    private val MIRROR = t("mirror", "mirror", "mirrors", "शीशा", "शीशे")
    private val PILLOW = t("pillow", "pillow", "pillows", "तकिया", "तकिए")
    private val TOWEL = t("towel", "towel", "towels", "तौलिया", "तौलिए")
    private val BOX = t("box", "box", "boxes", "डिब्बा", "डिब्बे")
    private val BIN = t("bin", "bin", "bins", "कूड़ेदान")
    private val PICTURE = t("picture", "picture", "pictures", "तस्वीर", "तस्वीरें")
    private val SWITCH = t("switch", "light switch", "light switches", "स्विच")
    private val SOCKET = t("socket", "plug socket", "plug sockets", "सॉकेट")
    private val CANDLE = t("candle", "candle", "candles", "मोमबत्ती", "मोमबत्तियाँ")
    private val CLOCK = t("clock", "clock", "clocks", "घड़ी", "घड़ियाँ")
    private val WATCH = t("watch", "wristwatch", "wristwatches", "कलाई घड़ी", "कलाई घड़ियाँ")
    private val TOILET = t("toilet", "toilet", "toilets", "टॉयलेट")
    private val SINK = t("sink", "sink", "sinks", "सिंक")
    private val TAP = t("tap", "tap", "taps", "नल")
    private val BATHTUB = t("bathtub", "bathtub", "bathtubs", "बाथटब")
    private val FRIDGE = t("fridge", "fridge", "fridges", "फ़्रिज")
    private val MICROWAVE = t("microwave", "microwave", "microwaves", "माइक्रोवेव")
    private val OVEN = t("oven", "oven", "ovens", "ओवन")
    private val TOASTER = t("toaster", "toaster", "toasters", "टोस्टर")
    private val WASHING_MACHINE = t("washing machine", "washing machine", "washing machines", "वॉशिंग मशीन")
    private val SCREEN = t("screen", "TV or computer screen", "TV or computer screens", "टीवी या स्क्रीन")
    private val LAPTOP = t("laptop", "laptop", "laptops", "लैपटॉप")
    private val KEYBOARD = t("keyboard", "keyboard", "keyboards", "कीबोर्ड")
    private val MOUSE = t("mouse", "computer mouse", "computer mice", "माउस")
    private val REMOTE = t("remote", "remote control", "remote controls", "रिमोट")
    private val PHONE = t("phone", "phone", "phones", "फ़ोन")
    private val TABLET = t("tablet", "tablet", "tablets", "टैबलेट")
    private val HEADPHONES = t("headphones", "pair of headphones", "pairs of headphones", "हेडफ़ोन")
    private val CALCULATOR = t("calculator", "calculator", "calculators", "कैलकुलेटर")
    private val BOOK = t("book", "book", "books", "किताब", "किताबें")
    private val PEN = t("pen", "pen", "pens", "पेन")
    private val WHITEBOARD = t("whiteboard", "whiteboard", "whiteboards", "व्हाइटबोर्ड")
    private val SCISSORS = t("scissors", "pair of scissors", "pairs of scissors", "कैंची", "कैंचियाँ")
    private val TEDDY = t("teddy", "teddy bear", "teddy bears", "टेडी बियर")
    private val HAIR_DRYER = t("hair dryer", "hair dryer", "hair dryers", "हेयर ड्रायर")
    private val TOOTHBRUSH = t("toothbrush", "toothbrush", "toothbrushes", "टूथब्रश")
    private val COIN = t("coin", "coin", "coins", "सिक्का", "सिक्के")
    private val GLASSES = t("glasses", "pair of glasses", "pairs of glasses", "चश्मा", "चश्मे")
    private val SHOE = t("shoe", "shoe", "shoes", "जूता", "जूते")
    private val HAT = t("hat", "hat", "hats", "टोपी", "टोपियाँ")
    private val HELMET = t("helmet", "helmet", "helmets", "हेलमेट")
    private val WHEELCHAIR = t("wheelchair", "wheelchair", "wheelchairs", "व्हीलचेयर")
    private val CART = t("cart", "cart", "carts", "ठेला", "ठेले")

    // ---- model class names → things ---------------------------------------------------------

    /** COCO (lowercase). Dropped: parking meter, bear, zebra, giraffe, tie, frisbee, skis, snowboard, baseball glove, surfboard. */
    private val COCO: Map<String, Thing> = mapOf(
        "person" to PERSON, "bicycle" to BICYCLE, "car" to CAR, "motorcycle" to MOTORBIKE, "airplane" to PLANE,
        "bus" to BUS, "train" to TRAIN, "truck" to TRUCK, "boat" to BOAT, "traffic light" to TRAFFIC_LIGHT,
        "fire hydrant" to HYDRANT, "stop sign" to SIGN, "bench" to BENCH, "bird" to BIRD, "cat" to CAT, "dog" to DOG,
        "horse" to HORSE, "sheep" to SHEEP, "cow" to COW, "elephant" to ELEPHANT, "backpack" to BAG,
        "umbrella" to UMBRELLA, "handbag" to BAG, "suitcase" to SUITCASE, "sports ball" to BALL, "kite" to KITE,
        "baseball bat" to BAT, "skateboard" to SKATEBOARD, "tennis racket" to RACKET, "bottle" to BOTTLE,
        "wine glass" to GLASS, "cup" to CUP, "fork" to FORK, "knife" to KNIFE, "spoon" to SPOON, "bowl" to BOWL,
        "banana" to BANANA, "apple" to APPLE, "sandwich" to SANDWICH, "orange" to ORANGE, "broccoli" to BROCCOLI,
        "carrot" to CARROT, "hot dog" to HOT_DOG, "pizza" to PIZZA, "donut" to DOUGHNUT, "cake" to CAKE,
        "chair" to CHAIR, "couch" to SOFA, "potted plant" to PLANT, "bed" to BED, "dining table" to TABLE,
        "toilet" to TOILET, "tv" to SCREEN, "laptop" to LAPTOP, "mouse" to MOUSE, "remote" to REMOTE,
        "keyboard" to KEYBOARD, "cell phone" to PHONE, "microwave" to MICROWAVE, "oven" to OVEN, "toaster" to TOASTER,
        "sink" to SINK, "refrigerator" to FRIDGE, "book" to BOOK, "clock" to CLOCK, "vase" to VASE,
        "scissors" to SCISSORS, "teddy bear" to TEDDY, "hair drier" to HAIR_DRYER, "toothbrush" to TOOTHBRUSH,
    )

    /** Open Images V7 (Capitalised): mostly what COCO doesn't know (doors, windows, stairs, shelves…). */
    private val OIV7: Map<String, Thing> = mapOf(
        "Door" to DOOR, "Window" to WINDOW, "Curtain" to CURTAIN, "Window blind" to CURTAIN, "Stairs" to STAIRS,
        "Ladder" to LADDER, "Lamp" to LAMP, "Ceiling fan" to CEILING_FAN, "Mechanical fan" to FAN, "Mirror" to MIRROR,
        "Pillow" to PILLOW, "Towel" to TOWEL, "Shelf" to SHELF, "Bookcase" to SHELF, "Cabinetry" to CUPBOARD,
        "Cupboard" to CUPBOARD, "Wardrobe" to CUPBOARD, "Closet" to CUPBOARD, "Chest of drawers" to CUPBOARD,
        "Box" to BOX, "Waste container" to BIN, "Picture frame" to PICTURE, "Poster" to PICTURE,
        "Light switch" to SWITCH, "Power plugs and sockets" to SOCKET, "Candle" to CANDLE, "Coin" to COIN,
        "Glasses" to GLASSES, "Sunglasses" to GLASSES, "Footwear" to SHOE, "Hat" to HAT, "Sun hat" to HAT,
        "Fedora" to HAT, "Helmet" to HELMET, "Bicycle helmet" to HELMET, "Wheelchair" to WHEELCHAIR, "Cart" to CART,
        "Tree" to TREE, "Palm tree" to TREE, "Building" to BUILDING, "House" to BUILDING, "Office building" to BUILDING,
        "Skyscraper" to BUILDING, "Street light" to POLE, "Traffic sign" to SIGN, "Stop sign" to SIGN,
        "Traffic light" to TRAFFIC_LIGHT, "Headphones" to HEADPHONES, "Calculator" to CALCULATOR,
        "Whiteboard" to WHITEBOARD, "Pen" to PEN, "Watch" to WATCH, "Wall clock" to CLOCK, "Digital clock" to CLOCK,
        "Alarm clock" to CLOCK, "Mobile phone" to PHONE, "Telephone" to PHONE, "Corded phone" to PHONE,
        "Tablet computer" to TABLET, "Computer monitor" to SCREEN, "Television" to SCREEN, "Plate" to PLATE,
        "Saucer" to PLATE, "Mug" to CUP, "Coffee cup" to CUP, "Jug" to JUG, "Pitcher (Container)" to JUG,
        "Kettle" to KETTLE, "Pressure cooker" to COOKER, "Gas stove" to STOVE, "Tap" to TAP, "Bathtub" to BATHTUB,
        "Washing machine" to WASHING_MACHINE, "Table" to TABLE, "Desk" to TABLE, "Coffee table" to TABLE,
        "Kitchen & dining room table" to TABLE, "Nightstand" to TABLE, "Stool" to STOOL, "Flower" to FLOWER,
        "Rose" to FLOWER, "Houseplant" to PLANT, "Flowerpot" to PLANT, "Bread" to BREAD, "Tomato" to TOMATO,
        "Potato" to POTATO, "Egg (Food)" to EGG, "Goat" to GOAT, "Monkey" to MONKEY, "Van" to VAN, "Taxi" to CAR,
    )

    private val ENTRIES: Map<String, VocabEntry> =
        COCO.mapValues { VocabEntry(it.value, COCO_MIN) } + OIV7.mapValues { VocabEntry(it.value, OIV7_MIN) }

    /** Every model class name to decode (pass to the detectors). */
    val LABELS: Set<String> = ENTRIES.keys

    /** How many different kinds of things can be named (for the honest "what I can recognise" note). */
    val KINDS: Int get() = things.size

    fun entry(label: String): VocabEntry? = ENTRIES[label]

    fun thing(key: String): Thing? = things[key]
}
