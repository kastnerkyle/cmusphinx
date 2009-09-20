/*
 * Copyright 1999-2002 Carnegie Mellon University.  
 * Portions Copyright 2002 Sun Microsystems, Inc.  
 * Portions Copyright 2002 Mitsubishi Electric Research Laboratories.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL 
 * WARRANTIES.
 *
 */

package edu.cmu.sphinx.linguist.language.ngram.large;

import edu.cmu.sphinx.linguist.WordSequence;
import edu.cmu.sphinx.linguist.dictionary.Dictionary;
import edu.cmu.sphinx.linguist.dictionary.Word;
import edu.cmu.sphinx.linguist.language.ngram.LanguageModel;
import edu.cmu.sphinx.util.LogMath;
import edu.cmu.sphinx.util.TimerPool;
import edu.cmu.sphinx.util.props.*;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Queries a binary language model file generated by the <a href="http://www.speech.cs.cmu.edu/SLM_info.html">
 * CMU-Cambridge Statistical Language Modeling Toolkit</a>.
 * <p/>
 * Note that all probabilities in the grammar are stored in LogMath log base format. Language Probabilities in the
 * language model file are stored in log 10 base. They are converted to the LogMath logbase.
 */
public class LargeTrigramModel implements LanguageModel {

    /**
     * A property for the name of the file that logs all the queried N-grams. If this property is set to null, it
     * means that the queried N-grams are not logged.
     */
    @S4String(mandatory = false)
    public static final String PROP_QUERY_LOG_FILE = "queryLogFile";

    /** A property that defines that maxium number of trigrams to be cached */
    @S4Integer(defaultValue = 100000)
    public static final String PROP_TRIGRAM_CACHE_SIZE = "trigramCacheSize";

    /** A property that defines the maximum number of bigrams to be cached. */
    @S4Integer(defaultValue = 50000)
    public static final String PROP_BIGRAM_CACHE_SIZE = "bigramCacheSize";

    /** A property that controls whether the bigram and trigram caches are cleared after every utterance */
    @S4Boolean(defaultValue = false)
    public static final String PROP_CLEAR_CACHES_AFTER_UTTERANCE = "clearCachesAfterUtterance";

    /** A property that defines the language weight for the search */
    @S4Double(defaultValue = 1.0)
    public final static String PROP_LANGUAGE_WEIGHT = "languageWeight";

    /** A property that defines the logMath component. */
    @S4Component(type = LogMath.class)
    public final static String PROP_LOG_MATH = "logMath";

    /**
     * A property that controls whether or not the language model will apply the language weight and word insertion
     * probability
     */
    @S4Boolean(defaultValue = false)
    public final static String PROP_APPLY_LANGUAGE_WEIGHT_AND_WIP = "applyLanguageWeightAndWip";

    /** Word insertion probability property */
    @S4Double(defaultValue = 1.0)
    public final static String PROP_WORD_INSERTION_PROBABILITY = "wordInsertionProbability";

    /** If true, use full bigram information to determine smear */
    @S4Boolean(defaultValue = false)
    public final static String PROP_FULL_SMEAR = "fullSmear";

    /**
     * The number of bytes per bigram in the LM file generated by the CMU-Cambridge Statistical Language Modelling
     * Toolkit.
     */
    public static final int BYTES_PER_BIGRAM = 8;

    /**
     * The number of bytes per trigram in the LM file generated by the CMU-Cambridge Statistical Language Modelling
     * Toolkit.
     */
    public static final int BYTES_PER_TRIGRAM = 4;

    private final static int SMEAR_MAGIC = 0xC0CAC01A; // things go better 

    public Logger getLogger() {
        return logger;
    }


    // ------------------------------
    // Configuration data
    // ------------------------------
    private Logger logger;
    private LogMath logMath;
    private String name;
    private String ngramLogFile;
    private int maxTrigramCacheSize;
    private int maxBigramCacheSize;
    private boolean clearCacheAfterUtterance;
    private boolean fullSmear;
    private int maxDepth;
    private Dictionary dictionary;
    private String format;
    private File location;
    private boolean applyLanguageWeightAndWip;
    private float languageWeight;
    private double wip;
    private float unigramWeight;

    // -------------------------------
    // Statistics
    // -------------------------------
    private int bigramMisses;
    private int trigramMisses;
    private int trigramHit;
    private int smearTermCount = 0;

    // -------------------------------
    // subcomponents
    // --------------------------------
    private BinaryLoader loader;
    private PrintWriter logFile;

    // -------------------------------
    // Working data
    // --------------------------------
    private Map<Word, UnigramProbability> unigramIDMap;
    private Map<WordSequence, TrigramBuffer> loadedTrigramBuffer;
    private LRUCache<WordSequence, Float> trigramCache;
    private LRUCache<WordSequence, BigramProbability> bigramCache;
    private Map<Long, Float> bigramSmearMap;

    private BigramBuffer[] loadedBigramBuffers;
    private UnigramProbability[] unigrams;
    private int[] trigramSegmentTable;
    private float[] bigramProbTable;
    private float[] trigramProbTable;
    private float[] trigramBackoffTable;
    private float[] unigramSmearTerm;


    /*
    * (non-Javadoc)
    *
    * @see edu.cmu.sphinx.util.props.Configurable#newProperties(edu.cmu.sphinx.util.props.PropertySheet)
    */
    public void newProperties(PropertySheet ps) throws PropertyException {
        logger = ps.getLogger();
        format = ps.getString(LanguageModel.PROP_FORMAT
        );
             
        URL urlLocation = ConfigurationManagerUtils.getResource(PROP_LOCATION, ps);
        location = new File (urlLocation.getFile());
        
        ngramLogFile = ps.getString(PROP_QUERY_LOG_FILE
        );
        maxTrigramCacheSize = ps.getInt(PROP_TRIGRAM_CACHE_SIZE
        );
        maxBigramCacheSize = ps.getInt(PROP_BIGRAM_CACHE_SIZE
        );
        clearCacheAfterUtterance = ps.getBoolean(
                PROP_CLEAR_CACHES_AFTER_UTTERANCE
        );
        maxDepth = ps.getInt(LanguageModel.PROP_MAX_DEPTH
        );
        logMath = (LogMath) ps.getComponent(PROP_LOG_MATH);
        dictionary = (Dictionary) ps.getComponent(PROP_DICTIONARY
        );
        applyLanguageWeightAndWip = ps.getBoolean(
                PROP_APPLY_LANGUAGE_WEIGHT_AND_WIP
        );
        languageWeight = ps.getFloat(PROP_LANGUAGE_WEIGHT
        );
        wip = ps.getDouble(PROP_WORD_INSERTION_PROBABILITY
        );
        unigramWeight = ps.getFloat(PROP_UNIGRAM_WEIGHT
        );

        fullSmear = ps.getBoolean(PROP_FULL_SMEAR);
    }


    /*
    * (non-Javadoc)
    *
    * @see edu.cmu.sphinx.util.props.Configurable#getName()
    */
    public String getName() {
        return name;
    }


    /*
    * (non-Javadoc)
    *
    * @see edu.cmu.sphinx.linguist.language.ngram.LanguageModel#allocate()
    */
    public void allocate() throws IOException {
        TimerPool.getTimer(this, "LM Load").start();
        // create the log file if specified
        if (ngramLogFile != null) {
            logFile = new PrintWriter(new FileOutputStream(ngramLogFile));
        }
        unigramIDMap = new HashMap<Word, UnigramProbability>();
        loadedTrigramBuffer = new HashMap<WordSequence, TrigramBuffer>();
        trigramCache = new LRUCache<WordSequence, Float>(maxTrigramCacheSize);
        bigramCache = new LRUCache<WordSequence, BigramProbability>(maxBigramCacheSize);
        loader = new BinaryLoader(format, location, applyLanguageWeightAndWip,
                logMath, languageWeight, wip, unigramWeight);
        unigrams = loader.getUnigrams();
        bigramProbTable = loader.getBigramProbabilities();
        trigramProbTable = loader.getTrigramProbabilities();
        trigramBackoffTable = loader.getTrigramBackoffWeights();
        trigramSegmentTable = loader.getTrigramSegments();
        buildUnigramIDMap(dictionary);
        loadedBigramBuffers = new BigramBuffer[unigrams.length];

	if (maxDepth <= 0 || maxDepth > loader.getMaxDepth()) {
            maxDepth = loader.getMaxDepth();
        }

        logger.info("Unigrams: " + loader.getNumberUnigrams());
        logger.info("Bigrams: " + loader.getNumberBigrams());
        logger.info("Trigrams: " + loader.getNumberTrigrams());

        if (fullSmear) {
            System.out.println("Full Smear");
            try {
                System.out.println("... Reading ...");
                readSmearInfo("smear.dat");
                System.out.println("... Done ");
            } catch (IOException e) {
                System.out.println("... " + e);
                System.out.println("... Calculating");
                buildSmearInfo();
                System.out.println("... Writing");
                // writeSmearInfo("smear.dat");
                System.out.println("... Done");
            }
        }
        TimerPool.getTimer(this,"LM Load").stop();

    }


    /*
    * (non-Javadoc)
    *
    * @see edu.cmu.sphinx.linguist.language.ngram.LanguageModel#deallocate()
    */
    public void deallocate() {
        // TODO write me
    }


    /** Builds the map from unigram to unigramID. Also finds the startWordID and endWordID. */
    private void buildUnigramIDMap(Dictionary dictionary) {
        int missingWords = 0;
        String[] words = loader.getWords();
        for (int i = 0; i < words.length; i++) {
            Word word = dictionary.getWord(words[i]);
            if (word == null) {
                logger.info("Missing word: " + words[i]);
                missingWords++;
            }
            unigramIDMap.put(word, unigrams[i]);
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Word: " + word);
            }
        }

        if (missingWords > 0) {
            logger.warning("Dictionary is missing " + missingWords
                    + " words that are contained in the language model.");
        }
    }


    /** Called before a recognition */
    public void start() {
        if (logFile != null) {
            logFile.println("<START_UTT>");
        }
    }


    /** Called after a recognition */
    public void stop() {
        clearCache();
        if (logFile != null) {
            logFile.println("<END_UTT>");
            logFile.flush();
        }
    }


    /** Clears the various N-gram caches. */
    private void clearCache() {
        for (int i = 0; i < loadedBigramBuffers.length; i++) {
            BigramBuffer buffer = loadedBigramBuffers[i];
            if (buffer != null) {
                if (!buffer.getUsed()) {
                    loadedBigramBuffers[i] = null; // free the BigramBuffer
                } else {
                    buffer.setUsed(false);
                }
            }
        }

        // loadedBigramBuffers = new BigramBuffer[unigrams.length];
        loadedTrigramBuffer = new HashMap<WordSequence, TrigramBuffer>();

        logger.info("LM Cache: 3-g " + trigramCache.size() + " 2-g "
                + bigramCache.size());

        if (clearCacheAfterUtterance) {
            trigramCache = new LRUCache<WordSequence, Float>(maxTrigramCacheSize);
            bigramCache = new LRUCache<WordSequence, BigramProbability>(maxBigramCacheSize);
        }
    }


    /**
     * Gets the ngram probability of the word sequence represented by the word list
     *
     * @param wordSequence the word sequence
     * @return the probability of the word sequence. Probability is in logMath log base
     */
    public float getProbability(WordSequence wordSequence) {
        if (logFile != null) {
            logFile.println(wordSequence.toString().replace("][", " "));
        }
        int numberWords = wordSequence.size();
        if (numberWords <= maxDepth) {
            if (numberWords == 3) {
                return getTrigramProbability(wordSequence);
            } else if (numberWords == 2) {
                return getBigramProbability(wordSequence);
            } else if (numberWords == 1) {
                return getUnigramProbability(wordSequence);
            }
        }
        throw new Error("Unsupported N-gram: " + wordSequence.size());
    }


    /**
     * Returns the unigram probability of the given unigram.
     *
     * @param wordSequence the unigram word sequence
     * @return the unigram probability
     */
    private float getUnigramProbability(WordSequence wordSequence) {
        Word unigram = wordSequence.getWord(0);
        UnigramProbability unigramProb = getUnigram(unigram);
        if (unigramProb == null) {
            throw new Error("Unigram not in LM: " + unigram);
        }
        return unigramProb.getLogProbability();
    }


    /**
     * Returns its UnigramProbability if this language model has the given unigram.
     *
     * @param unigram the unigram to find
     * @return the UnigramProbability, or null if this language model does not have the unigram
     */
    private UnigramProbability getUnigram(Word unigram) {
        return (UnigramProbability) unigramIDMap.get(unigram);
    }


    /**
     * Returns true if this language model has the given unigram.
     *
     * @param unigram the unigram to find
     * @return true if this LM has this unigram, false otherwise
     */
    private boolean hasUnigram(Word unigram) {
        return (unigramIDMap.get(unigram) != null);
    }


    /**
     * Returns the ID of the given word.
     *
     * @param word the word to find the ID
     * @return the ID of the word
     */
    public final int getWordID(Word word) {
        UnigramProbability probability = getUnigram(word);
        if (probability == null) {
            throw new IllegalArgumentException("No word ID: " + word);
        } else {
            return probability.getWordID();
        }
    }


    /**
     * Gets the smear term for the given wordSequence
     *
     * @param wordSequence the word sequence
     * @return the smear term associated with this word sequence
     */
    public float getSmearOld(WordSequence wordSequence) {
        float smearTerm = 0.0f;
        if (fullSmear) {
            int length = wordSequence.size();
            if (length > 0) {
                int wordID = getWordID(wordSequence.getWord(length - 1));
                smearTerm = unigramSmearTerm[wordID];
            }
        }
        if (fullSmear && logger.isLoggable(Level.FINE)) {
            logger.fine("SmearTerm: " + smearTerm);
        }
        return smearTerm;
    }


    int smearCount;
    int smearBigramHit;


    public float getSmear(WordSequence wordSequence) {
        float smearTerm = 1.0f;
        if (fullSmear) {
            smearCount++;
            int length = wordSequence.size();
            if (length == 1) {
                int wordID = getWordID(wordSequence.getWord(0));
                smearTerm = unigramSmearTerm[wordID];
            } else if (length >= 2) {
                int size = wordSequence.size();
                int wordID1 = getWordID(wordSequence.getWord(size - 2));
                int wordID2 = getWordID(wordSequence.getWord(size - 1));
                Float st = getSmearTerm(wordID1, wordID2);
                if (st == null) {
                    smearTerm = unigramSmearTerm[wordID2];
                } else {
                    smearTerm = st.floatValue();
                    smearBigramHit++;
                }
            }

            if (smearCount % 100000 == 0) {
                System.out.println("Smear hit: " + smearBigramHit +
                        " tot: " + smearCount);
            }
        }
        if (fullSmear && logger.isLoggable(Level.FINE)) {
            logger.fine("SmearTerm: " + smearTerm);
        }
        return smearTerm;
    }


    /**
     * Returns the unigram probability of the given unigram.
     *
     * @param wordSequence the unigram word sequence
     * @return the unigram probability
     */
    private float getBigramProbability(WordSequence wordSequence) {

        Word firstWord = wordSequence.getWord(0);
        if (loader.getNumberBigrams() <= 0 || !hasUnigram(firstWord)) {
            return getUnigramProbability(wordSequence.getNewest());
        }

        BigramProbability bigramProbability = findBigram(wordSequence);

        if (bigramProbability != null) {
            return bigramProbTable[bigramProbability.getProbabilityID()];
        } else {
            Word secondWord = wordSequence.getWord(1);

            if (getUnigram(secondWord) == null) {
                throw new Error("Bad word2: " + secondWord);
            }

            // System.out.println("Didn't find bigram");
            int firstWordID = getWordID(firstWord);
            int secondWordID = getWordID(secondWord);
            bigramMisses++;
            return (unigrams[firstWordID].getLogBackoff() + unigrams[secondWordID]
                    .getLogProbability());
        }
    }


    /**
     * Finds the BigramProbability for a particular bigram
     *
     * @param ws the word sequence
     * @return the BigramProbability of the bigram, or null if the given first word has no bigrams
     */
    private BigramProbability findBigram(WordSequence ws) {

        BigramProbability bigramProbability = (BigramProbability) bigramCache
                .get(ws);

        if (bigramProbability == null) {
            int firstWordID = getWordID(ws.getWord(0));
            int secondWordID = getWordID(ws.getWord(1));

            if (firstWordID < 0)
            	System.out.println (ws.getWord(0));
            
            BigramBuffer bigrams = getBigramBuffer(firstWordID);

            if (bigrams != null) {
                bigrams.setUsed(true);
                bigramProbability = bigrams.findBigram(secondWordID);
                if (bigramProbability != null) {
                    bigramCache.put(ws, bigramProbability);
                }
            }
        }

        return bigramProbability;
    }


    /**
     * Returns the bigrams of the given word
     *
     * @param firstWordID the ID of the word
     * @return the bigrams of the word
     */
    private BigramBuffer getBigramBuffer(int firstWordID) {
        BigramBuffer bigramBuffer = loadedBigramBuffers[firstWordID];
        if (bigramBuffer == null) {
            int numberBigrams = getNumberBigramFollowers(firstWordID);
            if (numberBigrams > 0) {
                bigramBuffer = loadBigramBuffer(firstWordID, numberBigrams);
                if (bigramBuffer != null) {
                    loadedBigramBuffers[firstWordID] = bigramBuffer;
                }
            }
        }
        return bigramBuffer;
    }


    /**
     * Loads the bigram followers of the given first word in a bigram from disk to memory. It actually loads
     * (numberFollowers + 1) bigrams, since we need the first bigram of the next word to determine the number of
     * trigrams of the last bigram.
     *
     * @param firstWordID     ID of the first word
     * @param numberFollowers the number of bigram followers this word has
     * @return the bigram followers of the given word
     */
    private BigramBuffer loadBigramBuffer(int firstWordID, int numberFollowers) {
        BigramBuffer followers = null;
        int firstBigramEntry = unigrams[firstWordID].getFirstBigramEntry();
        int size = (numberFollowers + 1) * BYTES_PER_BIGRAM;
        long position = (long) (loader.getBigramOffset() + (firstBigramEntry * BYTES_PER_BIGRAM));
        try {
            byte[] buffer = loader.loadBuffer(position, size);
            followers = new BigramBuffer(buffer, numberFollowers + 1, loader
                    .getBigEndian());
        } catch (IOException ioe) {
            ioe.printStackTrace();
            throw new Error("Error loading bigram followers");
        }

        return followers;
    }


    /**
     * Returns the number of bigram followers of a word.
     *
     * @param wordID the ID of the word
     * @return the number of bigram followers
     */
    private int getNumberBigramFollowers(int wordID) {
        if (wordID == unigrams.length - 1) {
            return 0;
        } else {
            return unigrams[wordID + 1].getFirstBigramEntry()
                    - unigrams[wordID].getFirstBigramEntry();
        }
    }


    /**
     * Returns the language probability of the given trigram.
     *
     * @param wordSequence the trigram word sequence
     * @return the trigram probability
     */
    private float getTrigramProbability(WordSequence wordSequence) {
        Word firstWord = wordSequence.getWord(0);

        if (loader.getNumberTrigrams() == 0 || !hasUnigram(firstWord)) {
            return getBigramProbability(wordSequence.getNewest());
        }

        Float probability = (Float) trigramCache.get(wordSequence);

        if (probability == null) {
            float score = 0.0f;

            int trigramProbID = findTrigram(wordSequence);

            if (trigramProbID != -1) {
                trigramHit++;
                score = trigramProbTable[trigramProbID];
            } else {
                trigramMisses++;
                BigramProbability bigram = findBigram(wordSequence.getOldest());
                if (bigram != null) {
                    score = trigramBackoffTable[bigram.getBackoffID()]
                            + getBigramProbability(wordSequence.getNewest());
                } else {
                    score = getBigramProbability(wordSequence.getNewest());
                }
            }
            probability = score;
            trigramCache.put(wordSequence, probability);
        }

        return probability.floatValue();
    }


    /**
     * Finds or loads the trigram probability of the given trigram.
     *
     * @param wordSequence the trigram to load
     * @return a TrigramProbability of the given trigram
     */
    private int findTrigram(WordSequence wordSequence) {

        int trigram = -1;

        WordSequence oldest = wordSequence.getOldest();
        TrigramBuffer trigramBuffer = (TrigramBuffer) loadedTrigramBuffer
                .get(oldest);

        if (trigramBuffer == null) {

            int firstWordID = getWordID(wordSequence.getWord(0));
            int secondWordID = getWordID(wordSequence.getWord(1));

            trigramBuffer = loadTrigramBuffer(firstWordID, secondWordID);

            if (trigramBuffer != null) {
                loadedTrigramBuffer.put(oldest, trigramBuffer);
            }
        }

        if (trigramBuffer != null) {
            int thirdWordID = getWordID(wordSequence.getWord(2));
            trigram = trigramBuffer.findProbabilityID(thirdWordID);
        }

        return trigram;
    }


    /**
     * Loads into a buffer all the trigram followers of the given bigram.
     *
     * @param firstWordID  the ID of the first word
     * @param secondWordID the ID of the second word
     * @return a TrigramBuffer of all the trigram followers of the given two words
     */
    private TrigramBuffer loadTrigramBuffer(int firstWordID, int secondWordID) {
        TrigramBuffer trigramBuffer = null;

        BigramBuffer bigramBuffer = getBigramBuffer(firstWordID);

        if (bigramBuffer != null) {
            BigramProbability bigram = bigramBuffer.findBigram(secondWordID);

            if (bigram != null) {

                BigramProbability nextBigram = bigramBuffer
                        .getBigramProbability(bigram.getWhichFollower() + 1);

                int firstBigramEntry = unigrams[firstWordID]
                        .getFirstBigramEntry();
                int firstTrigramEntry = getFirstTrigramEntry(bigram,
                        firstBigramEntry);
                int numberTrigrams = getFirstTrigramEntry(nextBigram,
                        firstBigramEntry)
                        - firstTrigramEntry;
                int size = numberTrigrams * BYTES_PER_TRIGRAM;
                long position = (loader.getTrigramOffset() + (long) (firstTrigramEntry * BYTES_PER_TRIGRAM));

                try {
                    // System.out.println("Loading TrigramBuffer from disk");
                    byte[] buffer = loader.loadBuffer(position, size);
                    trigramBuffer = new TrigramBuffer(buffer, numberTrigrams,
                            loader.getBigEndian());
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                    throw new Error("Error loading trigrams.");
                }
            }
        }
        return trigramBuffer;
    }


    /**
     * Returns the index of the first trigram entry of the given bigram
     *
     * @param bigram           the bigram which first trigram entry we're looking for
     * @param firstBigramEntry the index of the first bigram entry of the bigram in question
     * @return the index of the first trigram entry of the given bigram
     */
    private int getFirstTrigramEntry(BigramProbability bigram,
                                     int firstBigramEntry) {
        int firstTrigramEntry = trigramSegmentTable[(firstBigramEntry + bigram
                .getWhichFollower()) >> loader.getLogBigramSegmentSize()]
                + bigram.getFirstTrigramEntry();
        return firstTrigramEntry;
    }


    /**
     * Returns the backoff probability for the give sequence of words
     *
     * @param wordSequence the sequence of words
     * @return the backoff probability in LogMath log base
     */
    public float getBackoff(WordSequence wordSequence) {
        float logBackoff = 0.0f; // log of 1.0
        UnigramProbability prob = null; //getProb(wordSequence);
        if (prob != null) {
            logBackoff = prob.getLogBackoff();
        }
        return logBackoff;
    }


    /**
     * Returns the maximum depth of the language model
     *
     * @return the maximum depth of the language model
     */
    public int getMaxDepth() {
        return maxDepth;
    }


    /**
     * Returns the set of words in the lanaguage model. The set is unmodifiable.
     *
     * @return the unmodifiable set of words
     */
    public Set<String> getVocabulary() {
        Set<String> vocabulary = new HashSet<String>();
        vocabulary.addAll(Arrays.asList(loader.getWords()));
        return Collections.unmodifiableSet(vocabulary);
    }


    /**
     * Returns the number of times when a bigram is queried, but there is no bigram in the LM (in which case it uses the
     * backoff probabilities).
     *
     * @return the number of bigram misses
     */
    public int getBigramMisses() {
        return bigramMisses;
    }


    /**
     * Returns the number of times when a trigram is queried, but there is no trigram in the LM (in which case it uses
     * the backoff probabilities).
     *
     * @return the number of trigram misses
     */
    public int getTrigramMisses() {
        return trigramMisses;
    }


    /**
     * Returns the number of trigram hits.
     *
     * @return the number of trigram hits
     */
    public int getTrigramHits() {
        return trigramHit;
    }


    private void buildSmearInfo() throws IOException {
        double S0 = 0;
        double R0 = 0;

        bigramSmearMap = new HashMap<Long, Float>();

        double[] ugNumerator = new double[unigrams.length];
        double[] ugDenominator = new double[unigrams.length];
        double[] ugAvgLogProb = new double[unigrams.length];

        unigramSmearTerm = new float[unigrams.length];

        for (int i = 0; i < unigrams.length; i++) {
            float logp = unigrams[i].getLogProbability();
            double p = logMath.logToLinear(logp);
            S0 += p * logp;
            R0 += p * logp * logp;
        }

        System.out.println("R0 S0 " + R0 + ' ' + S0);

        for (int i = 0; i < loadedBigramBuffers.length; i++) {
            BigramBuffer bigram = getBigramBuffer(i);
            if (bigram == null) {
                unigramSmearTerm[i] = LogMath.getLogOne();
                continue;
            }

            ugNumerator[i] = 0.0;
            ugDenominator[i] = 0.0;
            ugAvgLogProb[i] = 0.0;

            float logugbackoff = unigrams[i].getLogBackoff();
            double ugbackoff = logMath.logToLinear(logugbackoff);

            for (int j = 0; j < bigram.getNumberNGrams(); j++) {
                int wordID = bigram.getWordID(j);
                BigramProbability bgProb = bigram.getBigramProbability(j);

                float logugprob = unigrams[wordID].getLogProbability();
                float logbgprob = bigramProbTable[bgProb.getProbabilityID()];

                double ugprob = logMath.logToLinear(logugprob);
                double bgprob = logMath.logToLinear(logbgprob);

                double backoffbgprob = ugbackoff * ugprob;
                double logbackoffbgprob = logMath.linearToLog(backoffbgprob);

                ugNumerator[i] += (bgprob * logbgprob
                        - backoffbgprob * logbackoffbgprob) * logugprob;

                ugDenominator[i] += (bgprob - backoffbgprob) * logugprob;
                // dumpProbs(ugNumerator, ugDenominator, i, j, logugprob,
				//		logbgprob, ugprob, bgprob, backoffbgprob,
				//		logbackoffbgprob);
            }
            ugNumerator[i] += ugbackoff * (logugbackoff * S0 + R0);
            ugAvgLogProb[i] = ugDenominator[i] + ugbackoff * S0;
            ugDenominator[i] += ugbackoff * R0;

            // System.out.println("n/d " + ugNumerator[i] + " " +
            //                     ugDenominator[i]);

            unigramSmearTerm[i] = (float) (ugNumerator[i] / ugDenominator[i]);
            /// unigramSmearTerm[i] = 
            //   logMath.linearToLog(ugNumerator[i] / ugDenominator[i]);
            //  System.out.println("ugs " + unigramSmearTerm[i]);
        }

        for (int i = 0; i < loadedBigramBuffers.length; i++) {
            System.out.println("Processed " + i
                    + " of " + loadedBigramBuffers.length);
            BigramBuffer bigram = getBigramBuffer(i);
            if (bigram == null) {
                continue;
            }
            for (int j = 0; j < bigram.getNumberNGrams(); j++) {
                float smearTerm;
                BigramProbability bgProb = bigram.getBigramProbability(j);
                float logbgbackoff = trigramBackoffTable[bgProb.getBackoffID()];
                double bgbackoff = logMath.logToLinear(logbgbackoff);
                int k = bigram.getWordID(j);
                TrigramBuffer trigram = loadTrigramBuffer(i, k);

                if (trigram == null) {
                    smearTerm = unigramSmearTerm[k];
                } else {
                    double bg_numerator = 0;
                    double bg_denominator = 0;
                    for (int l = 0; l < trigram.getNumberNGrams(); l++) {
                        int m = trigram.getWordID(l);
                        float logtgprob
                                = trigramProbTable[trigram.getProbabilityID(l)];
                        double tgprob = logMath.logToLinear(logtgprob);
                        float logbgprob = getBigramProb(k, m);
                        double bgprob = logMath.logToLinear(logbgprob);
                        float logugprob = unigrams[m].getLogProbability();
                        double backofftgprob = bgbackoff * bgprob;
                        double logbackofftgprob
                                = logMath.linearToLog(backofftgprob);

                        bg_numerator += (tgprob * logtgprob
                                - backofftgprob * logbackofftgprob) * logugprob;

                        bg_denominator += (tgprob - backofftgprob)
                                * logugprob * logugprob;
                    }
                    bg_numerator += bgbackoff * (logbgbackoff *
                            ugAvgLogProb[k] - ugNumerator[k]);

                    bg_denominator += bgbackoff * ugDenominator[k];
                    // bigram.ugsmear = bg_numerator / bg_denominator;
                    smearTerm = (float) (bg_numerator / bg_denominator);
                    smearTermCount++;
                }
                putSmearTerm(i, k, smearTerm);
            }
        }
        System.out.println("Smear count is " + smearTermCount);
    }

	@SuppressWarnings("unused")
	private void dumpProbs(double[] ugNumerator, double[] ugDenominator, int i,
			int j, float logugprob, float logbgprob, double ugprob,
			double bgprob, double backoffbgprob, double logbackoffbgprob) {

		System.out.println("ubo " + ugprob + ' ' + bgprob + ' ' +
		            backoffbgprob);
		    System.out.println("logubo " + logugprob
		            + ' ' + logbgprob + ' ' + logbackoffbgprob);
		    System.out.println("n/d " + j + ' '
		            + ugNumerator[i] + ' ' + ugDenominator[i]);

		    
		    System.out.print(ugprob + " " + bgprob + ' '
		            + backoffbgprob);
		    System.out.print(" " + logugprob + ' '
		            + logbgprob + ' ' + logbackoffbgprob);
		    System.out.println("  " + ugNumerator[i]
		            + ' ' + ugDenominator[i]);
	}


    /**
     * Writes the smear info to the given file
     *
     * @param filename the file to write the smear info to
     * @throws IOException if an error occurs on write
     */
    @SuppressWarnings("unused")
	private void writeSmearInfo(String filename) throws IOException {
        DataOutputStream out
                = new DataOutputStream(new FileOutputStream(filename));
        out.writeInt(SMEAR_MAGIC);
        System.out.println("writing " + unigrams.length);
        out.writeInt(unigrams.length);

        for (int i = 0; i < unigrams.length; i++) {
            out.writeFloat(unigramSmearTerm[i]);
        }

        for (int i = 0; i < unigrams.length; i++) {
            System.out.println("Writing " + i
                    + " of " + unigrams.length);
            BigramBuffer bigram = getBigramBuffer(i);
            if (bigram == null) {
                out.writeInt(0);
                continue;
            }
            out.writeInt(bigram.getNumberNGrams());
            for (int j = 0; j < bigram.getNumberNGrams(); j++) {
                int k = bigram.getWordID(j);
                Float smearTerm = getSmearTerm(i, k);
                out.writeInt(k);
                out.writeFloat(smearTerm.floatValue());
            }
        }
        out.close();
    }


    /**
     * Reads the smear info from the given file
     *
     * @param filename where to read the smear info from
     * @throws IOException if an inconsistent file is found or on any general I/O error
     */
    private void readSmearInfo(String filename) throws IOException {
        DataInputStream in
                = new DataInputStream(new FileInputStream(filename));


        if (in.readInt() != SMEAR_MAGIC) {
            throw new IOException("Bad smear format for " + filename);
        }

        if (in.readInt() != unigrams.length) {
            throw new IOException("Bad unigram length in " + filename);
        }

        bigramSmearMap = new HashMap<Long, Float>();
        unigramSmearTerm = new float[unigrams.length];

        System.out.println("Reading " + unigrams.length);
        for (int i = 0; i < unigrams.length; i++) {
            unigramSmearTerm[i] = in.readFloat();
        }

        for (int i = 0; i < unigrams.length; i++) {
            System.out.println("Processed " + i
                    + " of " + loadedBigramBuffers.length);
            int numBigrams = in.readInt();
            BigramBuffer bigram = getBigramBuffer(i);
            if (bigram.getNumberNGrams() != numBigrams) {
                throw new IOException("Bad ngrams for unigram " + i
                        + " Found " + numBigrams + " expected " +
                        bigram.getNumberNGrams()
                );
            }
            for (int j = 0; j < numBigrams; j++) {
                int k = bigram.getWordID(j);
                putSmearTerm(i, k, in.readFloat());
            }
        }
        in.close();
    }


    /**
     * Puts the smear term for the two words
     *
     * @param word1     the first word
     * @param word2     the second word
     * @param smearTerm the smear term
     */
    private void putSmearTerm(int word1, int word2, float smearTerm) {
        long bigramID = (((long) word1) << 32) | word2;
        bigramSmearMap.put(bigramID, smearTerm);
    }


    /**
     * Retrieves the smear term for the two words
     *
     * @param word1 the first word
     * @param word2 the second word
     * @return the smear term
     */
    private Float getSmearTerm(int word1, int word2) {
        long bigramID = (((long) word1) << 32) | word2;
        return (Float) bigramSmearMap.get(bigramID);
    }


    /**
     * Retrieves the bigram probability for the two given words
     *
     * @param word1 the first word of the bigram
     * @param word2 the second word of the bigram
     * @return the log probability
     */
    private float getBigramProb(int word1, int word2) {
        BigramBuffer bigram = getBigramBuffer(word1);
        BigramProbability bigramProbability = bigram.findBigram(word2);
        return bigramProbTable[bigramProbability.getProbabilityID()];
    }


}

/** An LRU cache */

class LRUCache<K, V> extends LinkedHashMap<K, V> {

	private static final long serialVersionUID = 1L;
	int maxSize;


    /**
     * Creates an LRU cache with the given maximum size
     *
     * @param maxSize the maximum size of the cache
     */
    LRUCache(int maxSize) {
        this.maxSize = maxSize;
    }


    /**
     * Determines if the eldest entry in the map should be removed.
     *
     * @param eldest the eldest entry
     * @return true if the eldest entry should be removed
     */
    protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
        return size() > maxSize;
    }
}
