package org.multibit.hd.core.managers;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.store.WalletProtobufSerializer;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Protos;
import org.joda.time.DateTime;
import org.multibit.commons.concurrent.SafeExecutors;
import org.multibit.commons.crypto.AESUtils;
import org.multibit.commons.files.SecureFiles;
import org.multibit.commons.utils.Dates;
import org.multibit.hd.brit.core.extensions.MatcherResponseWalletExtension;
import org.multibit.hd.brit.core.extensions.SendFeeDtoWalletExtension;
import org.multibit.hd.brit.core.seed_phrase.Bip39SeedPhraseGenerator;
import org.multibit.hd.brit.core.seed_phrase.SeedPhraseGenerator;
import org.multibit.hd.core.config.Configurations;
import org.multibit.hd.core.config.Yaml;
import org.multibit.hd.core.crypto.EncryptedFileReaderWriter;
import org.multibit.hd.core.dto.*;
import org.multibit.hd.core.error_reporting.ExceptionHandler;
import org.multibit.hd.core.events.CoreEvents;
import org.multibit.hd.core.events.ShutdownEvent;
import org.multibit.hd.core.events.TransactionSeenEvent;
import org.multibit.hd.core.events.WalletLoadEvent;
import org.multibit.hd.core.exceptions.WalletLoadException;
import org.multibit.hd.core.exceptions.WalletSaveException;
import org.multibit.hd.core.exceptions.WalletVersionException;
import org.multibit.hd.core.extensions.WalletTypeExtension;
import org.multibit.hd.core.files.EncryptedWalletFile;
import org.multibit.hd.core.services.BackupService;
import org.multibit.hd.core.services.BitcoinNetworkService;
import org.multibit.hd.core.services.CoreServices;
import org.multibit.hd.core.utils.BitcoinNetwork;
import org.multibit.hd.core.utils.Collators;
import org.multibit.hd.core.wallet.UnconfirmedTransactionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.multibit.hd.core.dto.WalletId.*;

/**
 * <p>Manager to provide the following to core users:</p>
 * <ul>
 * <li>create wallet</li>
 * <li>save wallet wallet</li>
 * <li>load wallet wallet</li>
 * <li>tracks the current wallet and the list of wallet directories</li>
 * </ul>
 * <p/>
 */
public enum WalletManager implements WalletEventListener {

  INSTANCE {
    @Override
    public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
      // Emit an event so that GUI elements can update as required
      Coin value = tx.getValue(wallet);
      log.debug("Received transaction {} with value {}", tx, value);

      CoreEvents.fireTransactionSeenEvent(new TransactionSeenEvent(tx, value));
    }

    @Override
    public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
      // Emit an event so that GUI elements can update as required
      Coin value = tx.getValue(wallet);
      CoreEvents.fireTransactionSeenEvent(new TransactionSeenEvent(tx, value));
    }

    @Override
    public void onReorganize(Wallet wallet) {

    }

    @Override
    public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
      // Emit an event so that GUI elements can update as required
      if (tx != null) {
        Coin value = tx.getValue(wallet);
        CoreEvents.fireTransactionSeenEvent(new TransactionSeenEvent(tx, value));
      }
    }

    @Override
    public void onWalletChanged(Wallet wallet) {

    }

    @Override
    public void onKeysAdded(List<ECKey> keys) {

    }

    @Override
    public void onScriptsChanged(Wallet wallet, List<Script> scripts, boolean isAddingScripts) {

    }
  };

  private static final int AUTO_SAVE_DELAY = 60000; // milliseconds

  // TODO (GR) Refactor this to be injected
  private static final NetworkParameters networkParameters = BitcoinNetwork.current().get();

  private static final Logger log = LoggerFactory.getLogger(WalletManager.class);

  /**
   * The earliest possible HD wallet.
   * Set after discussions on the bitcoinj mailing list:
   * https://groups.google.com/forum/#!topic/bitcoinj/288mCHhLMrA
   */
  public static final String EARLIEST_HD_WALLET_DATE = "2014-05-01";

  public static final String WALLET_DIRECTORY_PREFIX = "mbhd";
  // The format of the wallet directories is WALLET_DIRECTORY_PREFIX + a wallet id.
  // A wallet id is 5 groups of 4 bytes in lowercase hex, with a "-' separator e.g. mbhd-11111111-22222222-33333333-44444444-55555555
  private static final String REGEX_FOR_WALLET_DIRECTORY = "^"
    + WALLET_DIRECTORY_PREFIX
    + WALLET_ID_SEPARATOR
    + "[0-9a-f]{8}"
    + WALLET_ID_SEPARATOR
    + "[0-9a-f]{8}"
    + WALLET_ID_SEPARATOR
    + "[0-9a-f]{8}"
    + WALLET_ID_SEPARATOR
    + "[0-9a-f]{8}"
    + WALLET_ID_SEPARATOR
    + "[0-9a-f]{8}$";

  private static final Pattern walletDirectoryPattern = Pattern.compile(REGEX_FOR_WALLET_DIRECTORY);

  /**
   * The wallet version number for protobuf encrypted wallets - compatible with MultiBit Classic
   */
  public static final int MBHD_WALLET_VERSION = 1;
  public static final String MBHD_WALLET_PREFIX = "mbhd";
  public static final String MBHD_WALLET_SUFFIX = ".wallet";
  public static final String MBHD_AES_SUFFIX = ".aes";
  public static final String MBHD_SUMMARY_SUFFIX = ".yaml";
  public static final String MBHD_WALLET_NAME = MBHD_WALLET_PREFIX + MBHD_WALLET_SUFFIX;
  public static final String MBHD_SUMMARY_NAME = MBHD_WALLET_PREFIX + MBHD_SUMMARY_SUFFIX;
  public static final int LOOK_AHEAD_SIZE = 50; // A smaller look ahead size than the bitcoinj default of 100 (speeds up syncing as te bloom filters are smaller)
  public static final long MAXIMUM_WALLET_CREATION_DELTA = 180 * 1000; // 3 minutes in millis

  private Optional<WalletSummary> currentWalletSummary = Optional.absent();

  private static final SecureRandom random = new SecureRandom();

  /**
   * The initialisation vector to use for AES encryption of output files (such as wallets)
   * There is no particular significance to the value of these bytes
   */
  private static final byte[] AES_INITIALISATION_VECTOR = new byte[]{(byte) 0xa3, (byte) 0x44, (byte) 0x39, (byte) 0x1f, (byte) 0x53, (byte) 0x83, (byte) 0x11,
    (byte) 0xb3, (byte) 0x29, (byte) 0x54, (byte) 0x86, (byte) 0x16, (byte) 0xc4, (byte) 0x89, (byte) 0x72, (byte) 0x3e};

  /**
   * The salt used for deriving the KeyParameter from the credentials in AES encryption for wallets
   */
  private static final byte[] SCRYPT_SALT = new byte[]{(byte) 0x35, (byte) 0x51, (byte) 0x03, (byte) 0x80, (byte) 0x75, (byte) 0xa3, (byte) 0xb0, (byte) 0xc5};

  private ListeningExecutorService walletExecutorService = null;

  /**
   * @return A copy of the AES initialisation vector
   */
  public static byte[] deprecatedFixedAesInitializationVector() {
    return Arrays.copyOf(AES_INITIALISATION_VECTOR, AES_INITIALISATION_VECTOR.length);
  }

  /**
   * @return A copy of the Scrypt salt
   */
  public static byte[] scryptSalt() {
    return Arrays.copyOf(SCRYPT_SALT, SCRYPT_SALT.length);
  }


  /**
   * A new wallet up to this amount of seconds old will have a regular sync performed on it and not be checkpointed.
   */
  private static final int ALLOWABLE_TIME_DELTA = 10;

  /**
   * Open the given wallet and hook it up to the blockchain and peergroup so that it receives notifications
   *
   * @param applicationDataDirectory The application data directory
   * @param walletId                 The wallet ID to locate the wallet
   * @param password                 The credentials to use to decrypt the wallet
   *
   * @return The wallet summary if found
   */
  public Optional<WalletSummary> openWalletFromWalletId(File applicationDataDirectory, WalletId walletId, CharSequence password) throws WalletLoadException {
    log.debug("openWalletFromWalletId called");
    Preconditions.checkNotNull(walletId, "'walletId' must be present");
    Preconditions.checkNotNull(password, "'credentials' must be present");

    this.currentWalletSummary = Optional.absent();

    // Ensure BackupManager knows where the wallets are
    BackupManager.INSTANCE.setApplicationDataDirectory(applicationDataDirectory);

    // Work out the list of available wallets in the application data directory
    List<File> walletDirectories = findWalletDirectories(applicationDataDirectory);

    // If a wallet directory is present try to load the wallet
    if (!walletDirectories.isEmpty()) {
      String walletIdPath = walletId.toFormattedString();
      // Match the wallet directory to the wallet data
      for (File walletDirectory : walletDirectories) {

        verifyWalletDirectory(walletDirectory);

        String walletDirectoryPath = walletDirectory.getAbsolutePath();
        if (walletDirectoryPath.contains(walletIdPath)) {
          // Found the required wallet directory - attempt to present the wallet
          WalletSummary walletSummary = loadFromWalletDirectory(walletDirectory, password);
          setCurrentWalletSummary(walletSummary);

          try {
            // Wallet is now created - finish off other configuration
            updateConfigurationAndCheckSync(createWalletRoot(walletId), walletDirectory, walletSummary, false, true);
          } catch (IOException ioe) {
            throw new WalletLoadException("Cannot load wallet with id: " + walletId, ioe);
          }

          break;
        }
      }
    } else {
      currentWalletSummary = Optional.absent();
    }

    return currentWalletSummary;
  }

  /**
   * <h1>THIS METHOD DOES NOT PRODUCE BIP32 COMPLIANT WALLETS !</h1>
   * <h1>THIS METHOD DOES NOT PRODUCE BIP32 COMPLIANT WALLETS !</h1>
   * <h1>THIS METHOD DOES NOT PRODUCE BIP32 COMPLIANT WALLETS !</h1>
   *
   * See: https://github.com/keepkey/multibit-hd/issues/445
   *
   * <p>Create a MBHD soft wallet from a seed.</p>
   * <p>This is stored in the specified directory.</p>
   * <p>The name of the wallet directory is derived from the seed.</p>
   * <p>If the wallet file already exists it is loaded and returned</p>
   * <p>Auto-save is hooked up so that the wallet is saved on modification</p>
   * <p>Synchronization is begun if required</p>
   *
   * @param applicationDataDirectory The application data directory containing the wallet
   * @param seed                     The byte array corresponding to the seed phrase to initialise the wallet
   * @param creationTimeInSeconds    The creation time of the wallet, in seconds since epoch
   * @param password                 The credentials to use to encrypt the wallet - if null then the wallet is not loaded
   * @param name                     The wallet name
   * @param notes                    Public notes associated with the wallet
   * @param performSynch             True if the wallet should immediately begin synchronization
   *
   * @return Wallet summary containing the wallet object and the walletId (used in storage etc)
   *
   * @throws IllegalStateException  if applicationDataDirectory is incorrect
   * @throws WalletLoadException    if there is already a wallet created but it could not be loaded
   * @throws WalletVersionException if there is already a wallet but the wallet version cannot be understood
   */
  public WalletSummary badlyGetOrCreateMBHDSoftWalletSummaryFromSeed(
    File applicationDataDirectory,
    byte[] seed,
    long creationTimeInSeconds,
    String password,
    String name,
    String notes,
    boolean performSynch) throws WalletLoadException, WalletVersionException, IOException {
    log.debug("badlyGetOrCreateMBHDSoftWalletSummaryFromSeed called");
    final WalletSummary walletSummary;

    // Create a wallet id from the seed to work out the wallet root directory
    final WalletId walletId = new WalletId(seed);
    String walletRoot = createWalletRoot(walletId);

    final File walletDirectory = WalletManager.getOrCreateWalletDirectory(applicationDataDirectory, walletRoot);
    log.debug("Wallet directory:\n'{}'", walletDirectory.getAbsolutePath());

    final File walletFile = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME);
    final File walletFileWithAES = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME + MBHD_AES_SUFFIX);

    boolean saveWalletYaml = false;
    boolean createdNew = false;
    if (walletFileWithAES.exists()) {
      log.debug("Discovered AES encrypted wallet file. Loading...");

      // There is already a wallet created with this root - if so load it and return that
      walletSummary = loadFromWalletDirectory(walletDirectory, password);

      setCurrentWalletSummary(walletSummary);
    } else {
      // Wallet file does not exist so create it below the known good wallet directory
      log.debug("Creating new wallet file...");

      // Create a wallet using the seed (no salt passphrase)
      // THIS METHOD CALL PRODUCES NON BIP32 COMPLIANT WALLETS !
      // The entropy should be passed in - not the seed bytes
      DeterministicSeed deterministicSeed = new DeterministicSeed(seed, "", creationTimeInSeconds);
      Wallet walletToReturn = Wallet.fromSeed(networkParameters, deterministicSeed);
      walletToReturn.setKeychainLookaheadSize(LOOK_AHEAD_SIZE);
      walletToReturn.encrypt(password);
      walletToReturn.setVersion(MBHD_WALLET_VERSION);

      // Save it now to ensure it is on the disk
      walletToReturn.saveToFile(walletFile);
      EncryptedFileReaderWriter.makeAESEncryptedCopyAndDeleteOriginal(walletFile, password);

      // Create a new wallet summary
      walletSummary = new WalletSummary(walletId, walletToReturn);
      walletSummary.setName(name);
      walletSummary.setNotes(notes);
      walletSummary.setWalletPassword(new WalletPassword(password, walletId));
      walletSummary.setWalletFile(walletFile);
      walletSummary.setWalletType(WalletType.MBHD_SOFT_WALLET);
      setCurrentWalletSummary(walletSummary);

      // Save the wallet YAML
      saveWalletYaml = true;
      createdNew = true;

      try {
        WalletManager.writeEncryptedPasswordAndBackupKey(walletSummary, seed, password);
      } catch (NoSuchAlgorithmException e) {
        throw new WalletLoadException("Could not store encrypted credentials and backup AES key", e);
      }
    }

    // Set wallet type
    walletSummary.getWallet().addOrUpdateExtension(new WalletTypeExtension(WalletType.MBHD_SOFT_WALLET));

    if (createdNew) {
      CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), true, CoreMessageKey.WALLET_LOADED_OK, null, Optional.<File>absent()));
    }

    // Wallet is now created - finish off other configuration and check if wallet needs syncing
    updateConfigurationAndCheckSync(walletRoot, walletDirectory, walletSummary, saveWalletYaml, performSynch);

    return walletSummary;
  }

  /**
   * <p>Create a MBHD soft wallet from a seed.</p>
   * <p>This is stored in the specified directory.</p>
   * <p>The name of the wallet directory is derived from the seed.</p>
   * <p>If the wallet file already exists it is loaded and returned</p>
   * <p>Auto-save is hooked up so that the wallet is saved on modification</p>
   * <p>Synchronization is begun if required</p>
   *
   * @param applicationDataDirectory The application data directory containing the wallet
   * @param entropy                  The entropy equivalent to the wallet words (seed phrase)
   *                                 This is the byte array equivalent to the random number you are using
   *                                 This is NOT the seed bytes, which have undergone Scrypt processing
   * @param seed                     The seed byte array (the seed phrase after Scrypt processing)
   * @param creationTimeInSeconds    The creation time of the wallet, in seconds since epoch
   * @param password                 The credentials to use to encrypt the wallet - if null then the wallet is not loaded
   * @param name                     The wallet name
   * @param notes                    Public notes associated with the wallet
   * @param performSynch             True if the wallet should immediately begin synchronization
   *
   * @return Wallet summary containing the wallet object and the walletId (used in storage etc)
   *
   * @throws IllegalStateException  if applicationDataDirectory is incorrect
   * @throws WalletLoadException    if there is already a wallet created but it could not be loaded
   * @throws WalletVersionException if there is already a wallet but the wallet version cannot be understood
   */
  public WalletSummary getOrCreateMBHDSoftWalletSummaryFromEntropy(
    File applicationDataDirectory,
    byte[] entropy,
    byte[] seed,
    long creationTimeInSeconds,
    String password,
    String name,
    String notes,
    boolean performSynch) throws WalletLoadException, WalletVersionException, IOException {
    log.debug("getOrCreateMBHDSoftWalletSummaryFromEntropy called, creation time: {}", new DateTime(creationTimeInSeconds * 1000));
    final WalletSummary walletSummary;

    // Create a wallet id from the seed to work out the wallet root directory
    // The seed bytes are used for backwards compatibility
    final WalletId walletId = new WalletId(seed);
    String walletRoot = createWalletRoot(walletId);

    final File walletDirectory = WalletManager.getOrCreateWalletDirectory(applicationDataDirectory, walletRoot);
    log.debug("Wallet directory:\n'{}'", walletDirectory.getAbsolutePath());

    final File walletFile = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME);
    final File walletFileWithAES = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME + MBHD_AES_SUFFIX);

    boolean saveWalletYaml = false;
    boolean createdNew = false;
    if (walletFileWithAES.exists()) {
      log.debug("Discovered AES encrypted wallet file. Loading...");

      // There is already a wallet created with this root - if so load it and return that
      walletSummary = loadFromWalletDirectory(walletDirectory, password);

      setCurrentWalletSummary(walletSummary);
    } else {
      // Wallet file does not exist so create it below the known good wallet directory
      log.debug("Creating new wallet file...");

      // Create a wallet using the entropy
      // DeterministicSeed constructor expects ENTROPY here
      DeterministicSeed deterministicSeed = new DeterministicSeed(entropy, "", creationTimeInSeconds);
      Wallet walletToReturn = Wallet.fromSeed(networkParameters, deterministicSeed);
      walletToReturn.setKeychainLookaheadSize(LOOK_AHEAD_SIZE);
      walletToReturn.encrypt(password);
      walletToReturn.setVersion(MBHD_WALLET_VERSION);

      // Save it now to ensure it is on the disk
      walletToReturn.saveToFile(walletFile);
      EncryptedFileReaderWriter.makeAESEncryptedCopyAndDeleteOriginal(walletFile, password);

      // Create a new wallet summary
      walletSummary = new WalletSummary(walletId, walletToReturn);
      walletSummary.setName(name);
      walletSummary.setNotes(notes);
      walletSummary.setWalletPassword(new WalletPassword(password, walletId));
      walletSummary.setWalletFile(walletFile);
      walletSummary.setWalletType(WalletType.MBHD_SOFT_WALLET_BIP32);
      setCurrentWalletSummary(walletSummary);

      // Save the wallet YAML
      saveWalletYaml = true;
      createdNew = true;

      try {
        // The seed bytes are used as the secret to encrypt the password (mainly for backwards compatibility)
        WalletManager.writeEncryptedPasswordAndBackupKey(walletSummary, seed, password);
      } catch (NoSuchAlgorithmException e) {
        throw new WalletLoadException("Could not store encrypted credentials and backup AES key", e);
      }
    }

    // Set wallet type
    walletSummary.getWallet().addOrUpdateExtension(new WalletTypeExtension(WalletType.MBHD_SOFT_WALLET_BIP32));

    if (createdNew) {
      CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), true, CoreMessageKey.WALLET_LOADED_OK, null, Optional.<File>absent()));
    }

    // Wallet is now created - finish off other configuration and check if wallet needs syncing
    updateConfigurationAndCheckSync(walletRoot, walletDirectory, walletSummary, saveWalletYaml, performSynch);

    return walletSummary;
  }

  /**
   * Create a Trezor / KeepKey hard wallet from an HD root node.
   * <p/>
   * This is stored in the specified application directory.
   * The name of the wallet directory is derived from the rootNode.
   * <p/>
   * If the wallet file already exists it is loaded and returned
   * <p/>
   * Auto-save is hooked up so that the wallet is saved on modification
   *
   * @param applicationDataDirectory The application data directory containing the wallet
   * @param rootNode                 The root node that will be used to initialise the wallet (e.g. a BIP44 node)
   * @param creationTimeInSeconds    The creation time of the wallet, in seconds since epoch
   * @param password                 The credentials to use to encrypt the wallet - if null then the wallet is not loaded
   * @param name                     The wallet name
   * @param notes                    Public notes associated with the wallet
   * @param performSync              True if the wallet should immediately begin synchronization
   *
   * @return Wallet summary containing the wallet object and the walletId (used in storage etc)
   *
   * @throws IllegalStateException  if applicationDataDirectory is incorrect
   * @throws WalletLoadException    if there is already a wallet created but it could not be loaded
   * @throws WalletVersionException if there is already a wallet but the wallet version cannot be understood
   */
  public WalletSummary getOrCreateTrezorCloneHardWalletSummaryFromRootNode(
          File applicationDataDirectory,
          DeterministicKey rootNode,
          long creationTimeInSeconds,
          String password,
          String name,
          String notes,
          boolean performSync) throws WalletLoadException, WalletVersionException, IOException {

    log.debug("getOrCreateTrezorCloneHardWalletSummaryFromRootNode called");

    // Create a wallet id from the rootNode to work out the wallet root directory
    final WalletId walletId = new WalletId(rootNode.getIdentifier());
    String walletRoot = createWalletRoot(walletId);

    final File walletDirectory = WalletManager.getOrCreateWalletDirectory(applicationDataDirectory, walletRoot);
    final File walletFile = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME);
    final File walletFileWithAES = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME + MBHD_AES_SUFFIX);

    final WalletSummary walletSummary;

    boolean createdNew = false;

    if (walletFileWithAES.exists()) {
      try {
        // There is already a wallet created with this root - if so load it and return that
        log.debug("Opening AES wallet:\n'{}'", walletFileWithAES.getAbsolutePath());
        walletSummary = loadFromWalletDirectory(walletDirectory, password);

        // Use any existing notes if none is specified
        if (Strings.isNullOrEmpty(notes) && !Strings.isNullOrEmpty(walletSummary.getNotes())) {
          notes = walletSummary.getNotes();
        }
      } catch (WalletLoadException e) {
        // Failed to decrypt the existing wallet/backups or something else went wrong
        log.error("Failed to load from wallet directory.");
        CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), false, CoreMessageKey.WALLET_FAILED_TO_LOAD, e, Optional.<File>absent()));
        throw e;
      }
    } else {
      log.debug("Wallet file does not exist. Creating...");

      // Create the containing directory if it does not exist
      if (!walletDirectory.exists()) {
        if (!walletDirectory.mkdir()) {
          IllegalStateException error = new IllegalStateException("The directory for the wallet '" + walletDirectory.getAbsoluteFile() + "' could not be created");
          CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), false, CoreMessageKey.WALLET_FAILED_TO_LOAD, error, Optional.<File>absent()));
          throw error;
        }
      }

      // Create a wallet using the root node
      DeterministicKey rootNodePubOnly = rootNode.dropPrivateBytes();
      log.debug("Watching wallet based on: {}", rootNodePubOnly);

      rootNodePubOnly.setCreationTimeSeconds(creationTimeInSeconds);

      Wallet walletToReturn = Wallet.fromWatchingKey(networkParameters, rootNodePubOnly, creationTimeInSeconds, rootNodePubOnly.getPath());
      walletToReturn.setKeychainLookaheadSize(LOOK_AHEAD_SIZE);
      walletToReturn.setVersion(MBHD_WALLET_VERSION);

      // Save it now to ensure it is on the disk
      walletToReturn.saveToFile(walletFile);
      EncryptedFileReaderWriter.makeAESEncryptedCopyAndDeleteOriginal(walletFile, password);

      // Create a new wallet summary
      walletSummary = new WalletSummary(walletId, walletToReturn);

      log.debug("Created new wallet in {}", walletFile);

      createdNew = true;
    }

    // Wallet summary cannot be null at this point
    walletSummary.setWalletFile(walletFile);
    walletSummary.setName(name);
    walletSummary.setNotes(notes);
    walletSummary.setWalletPassword(new WalletPassword(password, walletId));
    walletSummary.setWalletType(WalletType.TREZOR_HARD_WALLET);

    setCurrentWalletSummary(walletSummary);

    // Set wallet type
    walletSummary.getWallet().addOrUpdateExtension(new WalletTypeExtension(WalletType.TREZOR_HARD_WALLET));


    try {
      // The entropy based password from the Trezor is used for both the wallet password and for the backup's password
      WalletManager.writeEncryptedPasswordAndBackupKey(walletSummary, password.getBytes(Charsets.UTF_8), password);
    } catch (NoSuchAlgorithmException e) {
      WalletLoadException error = new WalletLoadException("Could not store encrypted credentials and backup AES key", e);
      CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), false, CoreMessageKey.WALLET_FAILED_TO_LOAD, error, Optional.<File>absent()));
      throw error;
    }

    if (createdNew) {
      CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), true, CoreMessageKey.WALLET_LOADED_OK, null, Optional.<File>absent()));
    }

    // Wallet is now created - finish off other configuration and check if wallet needs syncing
    // (Always save the wallet yaml as there was a bug in early Trezor wallets where it was not written out)
    updateConfigurationAndCheckSync(walletRoot, walletDirectory, walletSummary, true, performSync);

    return walletSummary;
  }

  /**
   * Create a Trezor or KeepKey soft wallet from a seed phrase
   * <p/>
   * This is stored in the specified application directory.
   * The name of the wallet directory is derived from the rootNode.
   * <p/>
   * If the wallet file already exists it is loaded and returned
   * <p/>
   * Auto-save is hooked up so that the wallet is saved on modification
   *
   * @param applicationDataDirectory The application data directory containing the wallet
   * @param seedPhrase               The BIP39 seed phrase to use to initialise the walelt
   * @param creationTimeInSeconds    The creation time of the wallet, in seconds since epoch
   * @param password                 The credentials to use to encrypt the wallet - if null then the wallet is not loaded
   * @param name                     The wallet name
   * @param notes                    Public notes associated with the wallet
   * @param performSync              True if the wallet should immediately begin synchronizing
   *
   * @return Wallet summary containing the wallet object and the walletId (used in storage etc)
   *
   * @throws IllegalStateException  if applicationDataDirectory is incorrect
   * @throws WalletLoadException    if there is already a wallet created but it could not be loaded
   * @throws WalletVersionException if there is already a wallet but the wallet version cannot be understood
   */
  public WalletSummary getOrCreateTrezorCloneSoftWalletSummaryFromSeedPhrase(
          File applicationDataDirectory,
          String seedPhrase,
          long creationTimeInSeconds,
          String password,
          String name,
          String notes,
          boolean performSync) throws UnreadableWalletException, WalletLoadException, WalletVersionException, IOException {

    log.debug("getOrCreateTrezorCloneSoftWalletSummaryFromSeedPhrase called");

    // Create a wallet id from the seed to work out the wallet root directory
    SeedPhraseGenerator seedGenerator = new Bip39SeedPhraseGenerator();
    List<String> seedPhraseList = Bip39SeedPhraseGenerator.split(seedPhrase);
    byte[] seed = seedGenerator.convertToSeed(seedPhraseList);

    final WalletId walletId = new WalletId(seed, getWalletIdSaltUsedInScryptForTrezorSoftWallets());
    String walletRoot = createWalletRoot(walletId);

    final File walletDirectory = WalletManager.getOrCreateWalletDirectory(applicationDataDirectory, walletRoot);
    final File walletFile = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME);
    final File walletFileWithAES = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME + MBHD_AES_SUFFIX);

    final WalletSummary walletSummary;

    boolean createdNew = false;

    if (walletFileWithAES.exists()) {
      try {
        // There is already a wallet created with this root - if so load it and return that
        log.debug("A wallet with name {} exists. Opening...", walletFileWithAES.getAbsolutePath());
        walletSummary = loadFromWalletDirectory(walletDirectory, password);
      } catch (WalletLoadException e) {
        // Failed to decrypt the existing wallet/backups
        log.error("Failed to load from wallet directory.");
        IllegalStateException error = new IllegalStateException("The wallet could not be opened");
        CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), false, CoreMessageKey.WALLET_FAILED_TO_LOAD, error, Optional.<File>absent()));
        throw error;

      }
    } else {
      log.debug("Wallet file does not exist. Creating...");

      // Create the containing directory if it does not exist
      if (!walletDirectory.exists()) {
        if (!walletDirectory.mkdir()) {
          throw new IllegalStateException("The directory for the wallet '" + walletDirectory.getAbsoluteFile() + "' could not be created");
        }
      }

      // Trezor uses BIP-44
      // BIP-44 starts from M/44h/0h/0h for soft wallets
      List<ChildNumber> trezorRootNodePathList = new ArrayList<>();
      trezorRootNodePathList.add(new ChildNumber(44 | ChildNumber.HARDENED_BIT));
      trezorRootNodePathList.add(new ChildNumber(ChildNumber.HARDENED_BIT));

      DeterministicKey trezorRootNode = HDKeyDerivation.createRootNodeWithPrivateKey(ImmutableList.copyOf(trezorRootNodePathList), seed);
      log.debug("Creating Trezor clone soft wallet with root node with path {}", trezorRootNode.getPath());

      // Create a KeyCrypter to encrypt the waller
      KeyCrypterScrypt keyCrypterScrypt = new KeyCrypterScrypt(EncryptedFileReaderWriter.makeScryptParameters(WalletManager.SCRYPT_SALT));

      // Create a wallet using the seed phrase and Trezor root node
      DeterministicSeed deterministicSeed = new DeterministicSeed(seed, seedPhraseList, creationTimeInSeconds);

      Wallet walletToReturn = Wallet.fromSeed(networkParameters, deterministicSeed, trezorRootNode.getPath(), password, keyCrypterScrypt);
      walletToReturn.setKeychainLookaheadSize(LOOK_AHEAD_SIZE);
      walletToReturn.setVersion(MBHD_WALLET_VERSION);

      // Save it now to ensure it is on the disk
      walletToReturn.saveToFile(walletFile);
      EncryptedFileReaderWriter.makeAESEncryptedCopyAndDeleteOriginal(walletFile, password);

      // Create a new wallet summary
      walletSummary = new WalletSummary(walletId, walletToReturn);

      createdNew = true;
    }

    // Wallet summary cannot be null
    walletSummary.setWalletFile(walletFile);
    walletSummary.setName(name);
    walletSummary.setNotes(notes);
    walletSummary.setWalletPassword(new WalletPassword(password, walletId));
    walletSummary.setWalletType(WalletType.TREZOR_SOFT_WALLET);

    setCurrentWalletSummary(walletSummary);

    // Set wallet type
    walletSummary.getWallet().addOrUpdateExtension(new WalletTypeExtension(WalletType.TREZOR_SOFT_WALLET));

    try {
      WalletManager.writeEncryptedPasswordAndBackupKey(walletSummary, seed, password);
    } catch (NoSuchAlgorithmException e) {
      throw new WalletLoadException("Could not store encrypted credentials and backup AES key", e);
    }

    if (createdNew) {
      CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), true, CoreMessageKey.WALLET_LOADED_OK, null, Optional.<File>absent()));
    }

    // Wallet is now created - finish off other configuration and check if wallet needs syncing
    // Always save the wallet YAML as there was a bug in early Trezor wallets where it was not written out
    updateConfigurationAndCheckSync(walletRoot, walletDirectory, walletSummary, true, performSync);

    return walletSummary;
  }


  /**
   * Update configuration with new wallet information
   */
  private void updateConfigurationAndCheckSync(
    String walletRoot,
    File walletDirectory,
    WalletSummary walletSummary,
    boolean saveWalletYaml,
    boolean performSync) throws IOException {

    Preconditions.checkNotNull(walletRoot, "'walletRoot' must be present");
    Preconditions.checkNotNull(walletDirectory, "'walletDirectory' must be present");
    Preconditions.checkNotNull(walletSummary, "'walletSummary' must be present");

    // Set the walletSummary walletType
    // This is stored in plain text in the wallet yaml and enables filtering before knowing the wallet password
    walletSummary.setWalletType(getWalletType(walletSummary.getWallet()));

    if (saveWalletYaml) {
      File walletSummaryFile = WalletManager.getOrCreateWalletSummaryFile(walletDirectory);
      log.debug("Writing wallet YAML to file:\n'{}'", walletSummaryFile.getAbsolutePath());
      WalletManager.updateWalletSummary(walletSummaryFile, walletSummary);
    }

    // Remember the current soft wallet root
    if (WalletType.MBHD_SOFT_WALLET == walletSummary.getWalletType() ||
      WalletType.MBHD_SOFT_WALLET_BIP32 == walletSummary.getWalletType() ||
      WalletType.TREZOR_SOFT_WALLET == walletSummary.getWalletType()) {
      if (Configurations.currentConfiguration != null) {
        Configurations.currentConfiguration.getWallet().setLastSoftWalletRoot(walletRoot);
      }
    }

    // See if there is a checkpoints file - if not then get the InstallationManager to copy one in
    File checkpointsFile = new File(walletDirectory.getAbsolutePath() + File.separator + InstallationManager.MBHD_PREFIX + InstallationManager.CHECKPOINTS_SUFFIX);
    InstallationManager.copyCheckpointsTo(checkpointsFile);

    // Set up auto-save on the wallet.
    addAutoSaveListener(walletSummary.getWallet(), walletSummary.getWalletFile());

    // Remember the info required for the next backups
    BackupService backupService = CoreServices.getOrCreateBackupService();
    backupService.rememberWalletSummaryAndPasswordForRollingBackup(walletSummary, walletSummary.getWalletPassword().getPassword());
    backupService.rememberWalletIdAndPasswordForLocalZipBackup(walletSummary.getWalletId(), walletSummary.getWalletPassword().getPassword());
    backupService.rememberWalletIdAndPasswordForCloudZipBackup(walletSummary.getWalletId(), walletSummary.getWalletPassword().getPassword());

    // Check if the wallet needs to synch (not required during FEST tests)
    if (performSync) {
      log.info("Wallet configured - performing synchronization");
      checkIfWalletNeedsToSync(walletSummary);
    } else {
      log.warn("Wallet configured - synchronization not selected - expect this during testing");
    }
  }

  /**
   * Check if the wallet needs to sync and, if so, work out the sync date and fire off the synchronise
   *
   * @param walletSummary The wallet summary containing the wallet that may need syncing
   */
  private void checkIfWalletNeedsToSync(WalletSummary walletSummary) {
    // See if the wallet and blockstore are at the same height - in which case perform a regular download blockchain
    // Else perform a sync from the last seen block date to ensure all tx are seen
    log.debug("Seeing if wallet needs to sync");
    if (walletSummary != null) {
      Wallet walletBeingReturned = walletSummary.getWallet();

      if (walletBeingReturned == null) {
        log.debug("There is no wallet to examine");
      } else {

        boolean performRegularSync = false;
        Optional<DateTime> unconfirmedTransactionReplayDate = Optional.absent();
        BlockStore blockStore = null;
        try {
          // Get the bitcoin network service
          BitcoinNetworkService bitcoinNetworkService = CoreServices.getOrCreateBitcoinNetworkService();
          log.debug("bitcoinNetworkService: {}", bitcoinNetworkService);

          int walletBlockHeight = walletBeingReturned.getLastBlockSeenHeight();
          Date walletLastSeenBlockTime = walletBeingReturned.getLastBlockSeenTime();

          log.debug(
            "Wallet lastBlockSeenHeight: {}, lastSeenBlockTime: {}, earliestKeyCreationTime: {}",
            walletBlockHeight,
            walletLastSeenBlockTime,
            new DateTime(walletBeingReturned.getEarliestKeyCreationTime() * 1000));

          // See if the bitcoinNetworkService already has an open blockstore
          blockStore = bitcoinNetworkService.getBlockStore();

          if (blockStore == null) {
            // Open the blockstore with no checkpointing (this is to get the chain height)
            blockStore = bitcoinNetworkService.openBlockStore(
              InstallationManager.getOrCreateApplicationDataDirectory(),
              new ReplayConfig()
            );
          }
          log.debug("blockStore = {}", blockStore);

          int blockStoreBlockHeight = -2;  // -2 is just a dummy value
          if (blockStore != null) {
            StoredBlock chainHead = blockStore.getChainHead();
            blockStoreBlockHeight = chainHead == null ? -2 : chainHead.getHeight();

          }
          log.debug("The blockStore is at height {}", blockStoreBlockHeight);

          boolean keyCreationTimeIsInThePast = false;
          if (walletBeingReturned.getEarliestKeyCreationTime() != -1) {
            if (walletBeingReturned.getEarliestKeyCreationTime() < Dates.nowInSeconds() - ALLOWABLE_TIME_DELTA) {
              keyCreationTimeIsInThePast = true;
            }
          }

          // Work out if the wallet has unconfirmed transactions in the time window of interest for replay
          unconfirmedTransactionReplayDate = UnconfirmedTransactionDetector.calculateReplayDate(walletBeingReturned, Dates.nowUtc());

          // If (wallet and block store match or wallet is brand new) and
          //    no sync is required due to unconfirmed transactions
          // then use regular sync
          if (((walletBlockHeight > 0 && walletBlockHeight == blockStoreBlockHeight) ||
            (walletLastSeenBlockTime == null && !keyCreationTimeIsInThePast)) && !unconfirmedTransactionReplayDate.isPresent()) {
            // Regular sync is ok - no need to use checkpoints / replayDate
            log.debug("Will perform a regular sync");
            performRegularSync = true;
          }
        } catch (BlockStoreException bse) {
          // Carry on - it's just logging
          log.warn("Block store exception", bse);
        } finally {
          // Close the blockstore - it will get opened again later but may or may not be checkpointed
          if (blockStore != null) {
            try {
              blockStore.close();
            } catch (BlockStoreException bse) {
              log.warn("Failed to close block store", bse);
            }
          }
        }

        if (performRegularSync) {
          synchroniseWallet(Optional.<DateTime>absent());
        } else {
          // Work out the replay date based on the last block seen, the earliest key creation date, the earliest HD wallet date
          // and the unconfirmed transaction replay date
          DateTime replayDate = calculateReplayDateTime(walletBeingReturned, unconfirmedTransactionReplayDate);
          synchroniseWallet(Optional.of(replayDate));
        }
      }
    }
  }

  /**
   * @param walletBeingReturned The wallet requiring replay
   * @param unconfirmedTransactionReplayDate The replayDate required due to there being unconfirmed transactions
   *
   * @return The most appropriate date time to being replay
   */
  private DateTime calculateReplayDateTime(Wallet walletBeingReturned, Optional<DateTime> unconfirmedTransactionReplayDate) {

    DateTime replayDateTime = null;

    // Start with the last block seen date
    if (walletBeingReturned.getLastBlockSeenTime() != null) {
      replayDateTime = new DateTime(walletBeingReturned.getLastBlockSeenTime());
      log.debug("Setting potential replay date from last block seen time of {}", replayDateTime);
    }

    // If there is an unconfirmedTransactionReplayDate and it is earlier then we will go back further to that
    if (unconfirmedTransactionReplayDate.isPresent()) {
      DateTime candidateReplayDate = unconfirmedTransactionReplayDate.get();
      if (candidateReplayDate != null && candidateReplayDate.isBefore(replayDateTime)) {
        replayDateTime = candidateReplayDate;
        log.debug("Setting earlier potential replay date from unconfirmedTransaction replay date of {}", replayDateTime);
      }
    }

    // Override with the earliest key creation date
    // Expect:
    // 0 for ECKey keys created before timestamp (triggers epoch) or
    // timestamp of "now" or "earliest key creation" in seconds since epoch
    long earliestKeyCreationSeconds = walletBeingReturned.getEarliestKeyCreationTime();
    if (earliestKeyCreationSeconds >= 0) {
      DateTime earliestKeyCreationDateTime = new DateTime(earliestKeyCreationSeconds * 1000); // Using seconds
      if (replayDateTime == null) {
        replayDateTime = earliestKeyCreationDateTime;
        log.debug("Setting potential replay date from earliestKeyCreationDateTime date (1) of {}", replayDateTime);
      }
    }

    // Override with earliest HD wallet date (shared with other wallets)
    DateTime earliestHDWalletDate = DateTime.parse(EARLIEST_HD_WALLET_DATE);
    if (replayDateTime == null || replayDateTime.isBefore(earliestHDWalletDate)) {
      // Do not go further back than earliest HD wallet (this avoids epoch)
      replayDateTime = earliestHDWalletDate;
      log.debug("Setting potential replay date from earliest HDwallet date of {}", replayDateTime);
    }

    // Cannot be null
    return replayDateTime;
  }

  /**
   * Load a wallet from a file and decrypt it
   * (but don't hook it up to the Bitcoin network or sync it)
   *
   * @param walletFile wallet file to load
   * @param password   password to use to decrypt the wallet
   *
   * @return the loaded wallet
   *
   * @throws IOException
   * @throws UnreadableWalletException
   */
  public Wallet loadWalletFromFile(File walletFile, CharSequence password) throws IOException, UnreadableWalletException {

    // Read the encrypted file in and decrypt it.
    byte[] fileBytes = Files.toByteArray(walletFile);
    byte[] ivBytes = Arrays.copyOfRange(fileBytes, 0, 16);
    byte[] encryptedWalletBytes = Arrays.copyOfRange(fileBytes, 16, fileBytes.length);
    Preconditions.checkNotNull(encryptedWalletBytes, "'encryptedWalletBytes' must be present");

    log.trace("Encrypted wallet bytes after load:\n{}", Utils.HEX.encode(encryptedWalletBytes));
    log.debug("Loaded the encrypted wallet bytes with length: {}", encryptedWalletBytes.length);

    KeyCrypterScrypt keyCrypterScrypt = new KeyCrypterScrypt(EncryptedFileReaderWriter.makeScryptParameters(SCRYPT_SALT));
    KeyParameter keyParameter = keyCrypterScrypt.deriveKey(password);

    // Decrypt the wallet bytes

      byte [] decryptedBytes = AESUtils.decrypt(encryptedWalletBytes, keyParameter, ivBytes);
      if(!EncryptedWalletFile.isParseable(decryptedBytes)){
          decryptedBytes = AESUtils.decrypt(fileBytes, keyParameter, WalletManager.deprecatedFixedAesInitializationVector());
      }
      InputStream inputStream = new ByteArrayInputStream(decryptedBytes);
      Protos.Wallet walletProto = WalletProtobufSerializer.parseToProto(inputStream);

      WalletExtension[] walletExtensions = new WalletExtension[]{new SendFeeDtoWalletExtension(), new MatcherResponseWalletExtension(), new WalletTypeExtension()};
      Wallet wallet = new WalletProtobufSerializer().readWallet(BitcoinNetwork.current().get(), walletExtensions, walletProto);
      wallet.setKeychainLookaheadSize(LOOK_AHEAD_SIZE);

      // Try to infer the wallet type from the key structure to bootstrap missing WalletType values
      inferWalletType(wallet);

      // Writing out a wallet to a clear text file is security risk
      // Do not do it except for debug
      // log.debug("Wallet loaded OK:\n{}\n", wallet);

      return wallet;

  }
  private void inferWalletType(Wallet wallet) {
    // Get the wallet type as defined by the wallet type extension
    WalletType walletType = getWalletType(wallet);

    WalletType inferredWalletType = null;

    if (WalletType.UNKNOWN.equals(walletType)) {
      // Attempt to infer the wallet type from the wallet key structure
      if (wallet.getActiveKeychain() != null) {
        List<DeterministicKey> leafKeys = wallet.getActiveKeychain().getLeafKeys();
        if (leafKeys != null && !leafKeys.isEmpty()) {
          DeterministicKey firstLeafKey = leafKeys.get(0);

          if (firstLeafKey != null) {
            ImmutableList<ChildNumber> firstLeafKeyPath = firstLeafKey.getPath();

            if (firstLeafKeyPath != null && firstLeafKeyPath.size() > 0) {
              // MBHD soft wallets start at m/0h
              if (ChildNumber.ZERO_HARDENED.equals(firstLeafKeyPath.get(0))) {
                inferredWalletType = WalletType.MBHD_SOFT_WALLET_BIP32;
              } else if ((new ChildNumber(44 | ChildNumber.HARDENED_BIT)).equals(firstLeafKeyPath.get(0))) {
                // Trezor wallet
                if (firstLeafKey.isEncrypted()) {
                  // soft wallets only have encrypted private keys
                  inferredWalletType = WalletType.TREZOR_SOFT_WALLET;
                } else {
                  inferredWalletType = WalletType.TREZOR_HARD_WALLET;
                }
              }
            }
          }
        }
      }

      // if we inferred the WalletType put it in the wallet
      if (inferredWalletType != null) {
        log.debug("Inferring the Wallet type of the wallet to be {}", inferredWalletType);
        wallet.addOrUpdateExtension(new WalletTypeExtension(inferredWalletType));
      }
    }
  }

  static public WalletType getWalletType(Wallet wallet) {
    if (wallet == null) {
      return WalletType.UNKNOWN;
    } else {
      Map<String, WalletExtension> walletExtensionMap = wallet.getExtensions();
      WalletTypeExtension walletTypeExtension = (WalletTypeExtension) walletExtensionMap.get(WalletTypeExtension.WALLET_TYPE_WALLET_EXTENSION_ID);
      if (walletTypeExtension == null) {
        return WalletType.UNKNOWN;
      } else {
        return walletTypeExtension.getWalletType();
      }
    }
  }

  /**
   * <p>Load up an encrypted Wallet from a specified wallet directory.</p>
   * <p>Reduced visibility for testing</p>
   *
   * @param walletDirectory The wallet directory containing the various wallet files to load
   * @param password        The credentials to use to decrypt the wallet
   *
   * @return Wallet - the loaded wallet
   *
   * @throws WalletLoadException    If the wallet could not be loaded
   * @throws WalletVersionException If the wallet has an unsupported version number
   */
  WalletSummary loadFromWalletDirectory(File walletDirectory, CharSequence password) throws WalletLoadException, WalletVersionException {

    Preconditions.checkNotNull(walletDirectory, "'walletDirectory' must be present");
    Preconditions.checkNotNull(password, "'credentials' must be present");
    verifyWalletDirectory(walletDirectory);

    try {
      String walletFilenameNoAESSuffix = walletDirectory.getAbsolutePath() + File.separator + MBHD_WALLET_NAME;
      File walletFile = new File(walletFilenameNoAESSuffix + MBHD_AES_SUFFIX);
      WalletId walletId = parseWalletFilename(walletFile.getAbsolutePath());

      if (walletFile.exists() && isWalletSerialised(walletFile)) {
        // Serialised wallets are no longer supported.
        throw new WalletLoadException(
          "Could not load wallet '"
            + walletFile
            + "'. Serialized wallets are no longer supported."
        );
      }

      Wallet wallet;
      boolean backupFileLoaded = false;

      try {
        wallet = loadWalletFromFile(walletFile, password);
      } catch (WalletVersionException wve) {
        // We want this exception to propagate out.
        // Don't bother trying to load the rolling backups as they will most likely be an unreadable version too.
        throw wve;
      } catch (Exception e) {
        // Log the initial error
        log.error("WalletManager error: " + e.getClass().getCanonicalName() + " " + e.getMessage(), e);

        // Try loading one of the rolling backups - this will send a WalletLoadedEvent containing the backup file loaded
        // If the rolling backups don't load then loadRollingBackup will throw a WalletLoadException which will propagate out
        wallet = BackupManager.INSTANCE.loadRollingBackup(walletId, password);
        backupFileLoaded = true;
      }

      // Create the wallet summary with its wallet
      WalletSummary walletSummary = getAndChangeWalletSummary(walletDirectory, walletId,password);
      walletSummary.setWallet(wallet);
      walletSummary.setWalletFile(new File(walletFilenameNoAESSuffix));
      walletSummary.setWalletPassword(new WalletPassword(password, walletId));

      log.debug("Loaded the wallet successfully from \n{}", walletDirectory);

      // Fire a wallet loaded event indicating success (if a rolling backup was loaded this has already been sent so do not send another)
      if (!backupFileLoaded) {
        CoreEvents.fireWalletLoadEvent(new WalletLoadEvent(Optional.of(walletId), true, CoreMessageKey.WALLET_LOADED_OK, null, Optional.<File>absent()));
      }
      File walletSummaryFile = getOrCreateWalletSummaryFile(walletDirectory);
      updateWalletSummary(walletSummaryFile,walletSummary);

      return walletSummary;

    } catch (WalletVersionException wve) {
      // We want this to propagate out as is
      throw wve;
    } catch (Exception e) {
      throw new WalletLoadException(e.getMessage(), e);
    }
  }

  /**
   * Set up auto-save on the wallet.
   * This ensures the wallet is saved on modification
   * The listener has a 'after save' callback which ensures rolling backups and local/ cloud backups are also saved where necessary
   *
   * @param wallet The wallet to add the autosave listener to
   * @param file   The file to add the autoSaveListener to - this should be WITHOUT the AES suffix
   */
  private void addAutoSaveListener(Wallet wallet, File file) {
    if (file != null) {
      WalletAutoSaveListener walletAutoSaveListener = new WalletAutoSaveListener();
      wallet.autosaveToFile(file, AUTO_SAVE_DELAY, TimeUnit.MILLISECONDS, walletAutoSaveListener);
      log.debug("WalletAutoSaveListener {} on file\n'{}'\njust added to wallet {}", System.identityHashCode(this), file.getAbsolutePath(), System.identityHashCode(wallet));
    } else {
      log.debug("Not adding autoSaveListener to wallet {} as no wallet file is specified", System.identityHashCode(wallet));
    }
  }

  /**
   * @param replayDate The date from which to replay the download (absent means no checkpoints)
   */
  private void synchroniseWallet(final Optional<DateTime> replayDate) {

    if (walletExecutorService == null) {
      walletExecutorService = SafeExecutors.newSingleThreadExecutor("sync-wallet");
    }

    // Start the Bitcoin network synchronization operation
    ListenableFuture<Boolean> future = walletExecutorService.submit(
      new Callable<Boolean>() {

        @Override
        public Boolean call() throws Exception {
          log.debug("Synchronizing wallet with replay date '{}'", replayDate.orNull());

          // Replay wallet, use fast catch up, no clearing mempool
          CoreServices.getOrCreateBitcoinNetworkService().replayWallet(
                  InstallationManager.getOrCreateApplicationDataDirectory(),
                  replayDate,
                  true,
                  false
          );
          return true;

        }

      });
    Futures.addCallback(
      future, new FutureCallback<Boolean>() {
        @Override
        public void onSuccess(@Nullable Boolean result) {
          // Do nothing this just means that the block chain download has begun
          log.debug("Sync has begun");

        }

        @Override
        public void onFailure(Throwable t) {
          // Have a failure
          log.debug("Sync failed, error was " + t.getClass().getCanonicalName() + " " + t.getMessage());

        }
      });
  }

  /**
   * @param walletFile the wallet to test serialisation for
   *
   * @return true if the wallet file specified is serialised (this format is no longer supported)
   */
  private boolean isWalletSerialised(File walletFile) {

    Preconditions.checkNotNull(walletFile, "'walletFile' must be present");
    Preconditions.checkState(walletFile.isFile(), "'walletFile' must be a file");

    boolean isWalletSerialised = false;
    InputStream stream = null;
    try {
      // Determine what kind of wallet stream this is: Java serialization or protobuf format
      stream = new BufferedInputStream(new FileInputStream(walletFile));
      isWalletSerialised = stream.read() == 0xac && stream.read() == 0xed;
    } catch (IOException e) {
      log.error(e.getClass().getCanonicalName() + " " + e.getMessage());
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (IOException e) {
          log.error(e.getClass().getCanonicalName() + " " + e.getMessage());
        }
      }
    }
    return isWalletSerialised;
  }

  /**
   * Create the name of the directory in which the wallet is stored
   *
   * @param walletId The wallet id to use (e.g. "11111111-22222222-33333333-44444444-55555555")
   *
   * @return A wallet root
   */
  public static String createWalletRoot(WalletId walletId) {

    Preconditions.checkNotNull(walletId, "'walletId' must be present");

    return WALLET_DIRECTORY_PREFIX + WALLET_ID_SEPARATOR + walletId.toFormattedString();
  }

  /**
   * <p>Get or create the sub-directory of the given application directory with the given wallet root</p>
   *
   * @param applicationDataDirectory The application data directory containing the wallet
   * @param walletRoot               The wallet root from which to make a sub-directory (e.g. "mbhd-11111111-22222222-33333333-44444444-55555555")
   *
   * @return The directory composed of parent directory plus the wallet root
   *
   * @throws IllegalStateException if wallet could not be created
   */
  public static File getOrCreateWalletDirectory(File applicationDataDirectory, String walletRoot) {

    // Create wallet directory under application directory
    File walletDirectory = SecureFiles.verifyOrCreateDirectory(applicationDataDirectory, walletRoot);

    // Sanity check the wallet directory name and existence
    verifyWalletDirectory(walletDirectory);

    return walletDirectory;
  }

  /**
   * @return A list of wallet summaries based on the current application directory contents (never null)
   */
  public static List<WalletSummary> getWalletSummaries() {

    List<File> walletDirectories = findWalletDirectories(InstallationManager.getOrCreateApplicationDataDirectory());
    Optional<String> walletRoot = INSTANCE.getCurrentWalletRoot();
    return findWalletSummaries(walletDirectories, walletRoot);

  }

  /**
   * <p>This list contains MBHD soft wallets and Trezor soft wallets</p>
   *
   * @param localeOptional the locale to sort results by
   *
   * @return A list of soft wallet summaries based on the current application directory contents (never null), ordered by wallet name
   */
  public static List<WalletSummary> getSoftWalletSummaries(final Optional<Locale> localeOptional) {

    List<File> walletDirectories = findWalletDirectories(InstallationManager.getOrCreateApplicationDataDirectory());
    Optional<String> walletRoot = INSTANCE.getCurrentWalletRoot();
    List<WalletSummary> allWalletSummaries = findWalletSummaries(walletDirectories, walletRoot);
    List<WalletSummary> softWalletSummaries = Lists.newArrayList();

    for (WalletSummary walletSummary : allWalletSummaries) {
      if (WalletType.MBHD_SOFT_WALLET == walletSummary.getWalletType()
        || WalletType.MBHD_SOFT_WALLET_BIP32 == walletSummary.getWalletType()
        || WalletType.TREZOR_SOFT_WALLET == walletSummary.getWalletType()) {
        softWalletSummaries.add(walletSummary);
      }
    }

    // Sort by name of wallet
    Collections.sort(
      softWalletSummaries, new Comparator<WalletSummary>() {
        @Override
        public int compare(WalletSummary me, WalletSummary other) {
          String myName = me.getName();
          if (myName == null) {
            myName = "";
          }
          String otherName = other.getName();
          if (otherName == null) {
            otherName = "";
          }
          return Collators.newCollator(localeOptional).compare(myName, otherName);
        }
      });

    return softWalletSummaries;
  }

  /**
   * <p>Work out what wallets are available in a directory (typically the user data directory).
   * This is achieved by looking for directories with a name like <code>"mbhd-walletId"</code>
   *
   * @param directoryToSearch The directory to search
   *
   * @return A list of files of wallet directories (never null)
   */
  public static List<File> findWalletDirectories(File directoryToSearch) {

    Preconditions.checkNotNull(directoryToSearch);

    File[] files = directoryToSearch.listFiles();
    List<File> walletDirectories = Lists.newArrayList();

    // Look for file names with format "mbhd"-"walletId" and are not empty
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          String filename = file.getName();
          if (filename.matches(REGEX_FOR_WALLET_DIRECTORY)) {
            // The name matches so add it
            walletDirectories.add(file);
          }
        }
      }
    }

    return walletDirectories;
  }

  /**
   * <p>Find Wallet summaries for all the wallet directories provided</p>
   *
   * @param walletDirectories The candidate wallet directory references
   * @param walletRoot        The wallet root of the first entry
   *
   * @return A list of wallet summaries (never null)
   */
  public static List<WalletSummary> findWalletSummaries(List<File> walletDirectories, Optional walletRoot) {

    Preconditions.checkNotNull(walletDirectories, "'walletDirectories' must be present");

    List<WalletSummary> walletList = Lists.newArrayList();
    for (File walletDirectory : walletDirectories) {
      if (walletDirectory.isDirectory()) {
        String directoryName = walletDirectory.getName();
        if (directoryName.matches(REGEX_FOR_WALLET_DIRECTORY)) {

          // The name matches so process it
          WalletId walletId = new WalletId(directoryName.substring(MBHD_WALLET_PREFIX.length() + 1));
          WalletSummary walletSummary = getOrCreateWalletSummary(walletDirectory, walletId);

          // Check if the wallet root is present and matches the file name
          if (walletRoot.isPresent() && directoryName.equals(walletRoot.get())) {
            walletList.add(0, walletSummary);
          } else {
            walletList.add(walletSummary);
          }
        }
      }
    }

    return walletList;
  }


  /**
   * Get the spendable balance of the current wallet
   * This is Optional.absent() if there is no wallet
   */
  public Optional<Coin> getCurrentWalletBalance() {
    Optional<WalletSummary> currentWalletSummary = getCurrentWalletSummary();
    if (currentWalletSummary.isPresent()) {
      // Use the real wallet data
      return Optional.of(currentWalletSummary.get().getWallet().getBalance());
    } else {
      // Unknown at this time
      return Optional.absent();
    }
  }

  /**
   * Get the balance of the current wallet including unconfirmed
   * This is Optional.absent() if there is no wallet
   */
  public Optional<Coin> getCurrentWalletBalanceWithUnconfirmed() {
    Optional<WalletSummary> currentWalletSummary = getCurrentWalletSummary();
    if (currentWalletSummary.isPresent()) {
      // Use the real wallet data
      return Optional.of(currentWalletSummary.get().getWallet().getBalance(Wallet.BalanceType.ESTIMATED));
    } else {
      // Unknown at this time
      return Optional.absent();
    }
  }

  /**
   * @return The current wallet summary (present only if a wallet has been unlocked)
   */
  public Optional<WalletSummary> getCurrentWalletSummary() {
    return currentWalletSummary;
  }

  /**
   * @param walletSummary The current wallet summary (null if a reset is required)
   */
  public void setCurrentWalletSummary(WalletSummary walletSummary) {

    if (walletSummary != null && walletSummary.getWallet() != null) {

      // Remove the previous WalletEventListener
      walletSummary.getWallet().removeEventListener(this);

      // Add the wallet event listener
      walletSummary.getWallet().addEventListener(this);
    }

    this.currentWalletSummary = Optional.fromNullable(walletSummary);

  }

  /**
   * @return The current wallet file (e.g. "/User/example/Application Support/MultiBitHD/mbhd-1111-2222-3333-4444/mbhd.wallet")
   */
  public Optional<File> getCurrentWalletFile(File applicationDataDirectory) {

    if (applicationDataDirectory != null && currentWalletSummary.isPresent()) {

      String walletFilename =
        applicationDataDirectory
          + File.separator
          + WALLET_DIRECTORY_PREFIX
          + WALLET_ID_SEPARATOR
          + currentWalletSummary.get().getWalletId().toFormattedString()
          + File.separator
          + MBHD_WALLET_NAME;
      return Optional.of(new File(walletFilename));

    } else {
      return Optional.absent();
    }

  }

  /**
   * @return The current wallet summary file (e.g. "/User/example/Application Support/MultiBitHD/mbhd-1111-2222-3333-4444/mbhd.yaml")
   */
  public Optional<File> getCurrentWalletSummaryFile(File applicationDataDirectory) {

    if (applicationDataDirectory != null && currentWalletSummary.isPresent()) {

      String walletFilename =
        applicationDataDirectory
          + File.separator
          + WALLET_DIRECTORY_PREFIX
          + WALLET_ID_SEPARATOR
          + currentWalletSummary.get().getWalletId().toFormattedString()
          + File.separator
          + MBHD_SUMMARY_NAME;
      return Optional.of(new File(walletFilename));

    } else {
      return Optional.absent();
    }

  }

  /**
   * @param walletDirectory The wallet directory containing the various wallet files
   *
   * @return A wallet summary file
   */
  public static File getOrCreateWalletSummaryFile(File walletDirectory) {
    return SecureFiles.verifyOrCreateFile(walletDirectory, MBHD_SUMMARY_NAME);
  }

  /**
   * @return The current wallet root as defined in the configuration, or absent
   */
  public Optional<String> getCurrentWalletRoot() {
    return Optional.fromNullable(Configurations.currentConfiguration.getWallet().getLastSoftWalletRoot());
  }

  /**
   * @param walletSummary The wallet summary to write
   */
  public static void updateWalletSummary(File walletSummaryFile, WalletSummary walletSummary) {

    if (walletSummary == null) {
      log.warn("WalletSummary is missing. The wallet configuration file is NOT being overwritten.");
      return;
    }

    // Persist the new configuration
    try (FileOutputStream fos = new FileOutputStream(walletSummaryFile)) {

      Yaml.writeYaml(fos, walletSummary);

    } catch (IOException e) {
      ExceptionHandler.handleThrowable(e);
    }
  }

  /**
   * @param walletDirectory The wallet directory to read
   *
   * @return The wallet summary if present, or a default if not
   */
  public static WalletSummary getOrCreateWalletSummary(File walletDirectory, WalletId walletId) {

    verifyWalletDirectory(walletDirectory);

    Optional<WalletSummary> walletSummaryOptional = Optional.absent();

    File walletSummaryFile = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_SUMMARY_NAME);
    if (walletSummaryFile.exists()) {
      try (InputStream is = new FileInputStream(walletSummaryFile)) {
        // Load configuration (providing a default if none exists)
        walletSummaryOptional = Yaml.readYaml(is, WalletSummary.class);
      } catch (IOException e) {
        // A full stack trace is too much here
        log.warn("Could not read wallet summary:\n'{}'\nException: {}", walletDirectory.getAbsolutePath(), e.getMessage());
      }
    }

    final WalletSummary walletSummary;
    if (walletSummaryOptional.isPresent()) {
      walletSummary = walletSummaryOptional.get();
    } else {
      walletSummary = new WalletSummary();
      // TODO No localiser available in core to localise core_default_wallet_name.
      String shortWalletDirectory = walletDirectory.getName().substring(0, 13); // The mbhd and the first group of digits
      walletSummary.setName("Wallet (" + shortWalletDirectory + "...)");
      walletSummary.setNotes("");
    }
    walletSummary.setWalletId(walletId);

    return walletSummary;

  }
  public static WalletSummary getAndChangeWalletSummary(File walletDirectory, WalletId walletId, CharSequence password) {

    verifyWalletDirectory(walletDirectory);

    Optional<WalletSummary> walletSummaryOptional = Optional.absent();

    File walletSummaryFile = new File(walletDirectory.getAbsolutePath() + File.separator + MBHD_SUMMARY_NAME);
    if (walletSummaryFile.exists()) {
      try (InputStream is = new FileInputStream(walletSummaryFile)) {
        // Load configuration (providing a default if none exists)
        walletSummaryOptional = Yaml.readYaml(is, WalletSummary.class);
      } catch (IOException e) {
        // A full stack trace is too much here
        log.warn("Could not read wallet summary:\n'{}'\nException: {}", walletDirectory.getAbsolutePath(), e.getMessage());
      }
    }

    final WalletSummary walletSummary;
    if (walletSummaryOptional.isPresent()) {
      walletSummary = walletSummaryOptional.get();
      try {
        changeEncryptedPasswordAndBackupKeyWithRandomIV(walletSummary,password);
      } catch (NoSuchAlgorithmException e) {
        e.printStackTrace();
      }
    } else {
      walletSummary = new WalletSummary();
      String shortWalletDirectory = walletDirectory.getName().substring(0, 13); // The mbhd and the first group of digits
      walletSummary.setName("Wallet (" + shortWalletDirectory + "...)");
      walletSummary.setNotes("");
    }
    walletSummary.setWalletId(walletId);

    return walletSummary;

  }

  /**
   * Write the encrypted wallet credentials and backup AES key to the wallet configuration.
   * You probably want to save it afterwards with an updateSummary
   *
   * @param walletSummary The wallet summary to write the encrypted details for
   * @param secret        The secret used to derive the AES encryption key. This is typically created deterministically from the wallet words
   * @param password      The password you want to store encrypted
   */
  public static void writeEncryptedPasswordAndBackupKey(WalletSummary walletSummary, byte[] secret, String password) throws NoSuchAlgorithmException {

    Preconditions.checkNotNull(walletSummary, "'walletSummary' must be present");
    Preconditions.checkNotNull(secret, "'secret' must be present");
    Preconditions.checkNotNull(password, "'password' must be present");

    // Save the wallet credentials, AES encrypted with a key derived from the wallet secret
    KeyParameter secretDerivedAESKey = org.multibit.commons.crypto.AESUtils.createAESKey(secret, SCRYPT_SALT);
    byte[] passwordBytes = password.getBytes(Charsets.UTF_8);
    SecureRandom secureRandom = new SecureRandom();
    byte[] ivBytes = new byte[16];
    secureRandom.nextBytes(ivBytes);
    walletSummary.setInitializationVector(ivBytes);
    byte[] paddedPasswordBytes = padPasswordBytes(passwordBytes);
    byte[] encryptedPaddedPassword = AESUtils.encrypt(paddedPasswordBytes, secretDerivedAESKey, ivBytes);
    walletSummary.setEncryptedPassword(encryptedPaddedPassword);

    // Save the backupAESKey, AES encrypted with a key generated from the wallet password
    KeyParameter walletPasswordDerivedAESKey = org.multibit.commons.crypto.AESUtils.createAESKey(passwordBytes, SCRYPT_SALT);
    byte[] encryptedBackupAESKey = AESUtils.encrypt(secretDerivedAESKey.getKey(), walletPasswordDerivedAESKey,ivBytes);
    walletSummary.setEncryptedBackupKey(encryptedBackupAESKey);
  }
  /**
   * Write the encrypted wallet credentials and backup AES key to the wallet configuration.
   * You probably want to save it afterwards with an updateSummary
   *
   * @param walletSummary The wallet summary to write the encrypted details for
   * @param password      The password you want to store encrypted
   */
  public static void changeEncryptedPasswordAndBackupKeyWithRandomIV(WalletSummary walletSummary,CharSequence password) throws NoSuchAlgorithmException {

    Preconditions.checkNotNull(walletSummary, "'walletSummary' must be present");
    Preconditions.checkNotNull(password, "'password' must be present");

    // Save the wallet credentials, AES encrypted with a key derived from the wallet secret

    byte[] passwordBytes = password.toString().getBytes(Charsets.UTF_8);
    KeyParameter walletPasswordDerivedAESKey = org.multibit.commons.crypto.AESUtils.createAESKey(passwordBytes, SCRYPT_SALT);
    byte[] encryptedSecretDerivedAESkey = walletSummary.getEncryptedBackupKey();
    KeyParameter secretDerivedAESKey = new KeyParameter(AESUtils.decrypt(encryptedSecretDerivedAESkey,walletPasswordDerivedAESKey,WalletManager.deprecatedFixedAesInitializationVector()));
    byte[] randomIvBytes = generateRandomIv();
    walletSummary.setInitializationVector(randomIvBytes);
    byte[] paddedPasswordBytes = padPasswordBytes(passwordBytes);
    byte[] encryptedPaddedPassword = AESUtils.encrypt(paddedPasswordBytes, secretDerivedAESKey,randomIvBytes);
    walletSummary.setEncryptedPassword(encryptedPaddedPassword);

    // Save the backupAESKey, AES encrypted with a key generated from the wallet password

    byte[] encryptedBackupAESKey = AESUtils.encrypt(secretDerivedAESKey.getKey(), walletPasswordDerivedAESKey,randomIvBytes);
    walletSummary.setEncryptedBackupKey(encryptedBackupAESKey);
  }

  /**
   * @param walletDirectory The candidate wallet directory (e.g. "/User/example/Application Support/MultiBitHD/mbhd-11111111-22222222-33333333-44444444-55555555")
   *
   * @throws IllegalStateException If the wallet directory is malformed
   */
  private static void verifyWalletDirectory(File walletDirectory) {

    log.trace("Verifying wallet directory: '{}'", walletDirectory.getAbsolutePath());

    Preconditions.checkState(walletDirectory.isDirectory(), "'walletDirectory' must be a directory: '" + walletDirectory.getAbsolutePath() + "'");

    // Use the pre-compiled regex
    boolean result = walletDirectoryPattern.matcher(walletDirectory.getName()).matches();

    Preconditions.checkState(result, "'walletDirectory' is not named correctly: '" + walletDirectory.getAbsolutePath() + "'");

    log.trace("Wallet directory verified ok");

  }

  /**
   * Method to determine whether a message is 'mine', meaning an existing address in the current wallet
   *
   * @param address The address to test for wallet inclusion
   *
   * @return true if address is in current wallet, false otherwise
   */
  public boolean isAddressMine(Address address) {
    try {

      Optional<WalletSummary> walletSummaryOptional = WalletManager.INSTANCE.getCurrentWalletSummary();

      if (walletSummaryOptional.isPresent()) {
        WalletSummary walletSummary = walletSummaryOptional.get();

        Wallet wallet = walletSummary.getWallet();
        ECKey signingKey = wallet.findKeyFromPubHash(address.getHash160());

        return signingKey != null;
      } else {
        // No wallet present
        return false;
      }
    } catch (Exception e) {
      // Some other problem
      return false;
    }
  }

  /**
   * @return True if current wallet is unlocked and represents a Trezor "hard" wallet
   */
  public boolean isUnlockedTrezorHardWallet() {
    try {

      Optional<WalletSummary> walletSummaryOptional = WalletManager.INSTANCE.getCurrentWalletSummary();

      if (walletSummaryOptional.isPresent()) {
        WalletSummary walletSummary = walletSummaryOptional.get();

        return WalletType.TREZOR_HARD_WALLET.equals(walletSummary.getWalletType());

      } else {
        // No wallet present
        return false;
      }
    } catch (Exception e) {
      // Some other problem
      return false;
    }
  }

  /**
   * <p>Method to sign a message</p>
   *
   * @param addressText    Text address to use to sign (makes UI Address conversion code DRY)
   * @param messageText    The message to sign
   * @param walletPassword The wallet credentials
   *
   * @return A "sign message result" describing the outcome
   */
  public SignMessageResult signMessage(String addressText, String messageText, String walletPassword) {
    if (Strings.isNullOrEmpty(addressText)) {
      return new SignMessageResult(Optional.<String>absent(), false, CoreMessageKey.SIGN_MESSAGE_ENTER_ADDRESS, null);
    }

    if (Strings.isNullOrEmpty(messageText)) {
      return new SignMessageResult(Optional.<String>absent(), false, CoreMessageKey.SIGN_MESSAGE_ENTER_MESSAGE, null);
    }

    if (Strings.isNullOrEmpty(walletPassword)) {
      return new SignMessageResult(Optional.<String>absent(), false, CoreMessageKey.SIGN_MESSAGE_ENTER_PASSWORD, null);
    }

    try {
      Address signingAddress = new Address(BitcoinNetwork.current().get(), addressText);

      Optional<WalletSummary> walletSummaryOptional = WalletManager.INSTANCE.getCurrentWalletSummary();

      if (walletSummaryOptional.isPresent()) {
        WalletSummary walletSummary = walletSummaryOptional.get();

        Wallet wallet = walletSummary.getWallet();

        ECKey signingKey = wallet.findKeyFromPubHash(signingAddress.getHash160());
        if (signingKey != null) {
          if (signingKey.getKeyCrypter() != null) {
            KeyParameter aesKey = signingKey.getKeyCrypter().deriveKey(walletPassword);
            ECKey decryptedSigningKey = signingKey.decrypt(aesKey);

            log.info("EXTRACTED private key: " + decryptedSigningKey.getPrivateKeyAsWiF(networkParameters));

            String signatureBase64 = decryptedSigningKey.signMessage(messageText);
            return new SignMessageResult(Optional.of(signatureBase64), true, CoreMessageKey.SIGN_MESSAGE_SUCCESS, null);
          } else {
            // The signing key is not encrypted but it should be
            return new SignMessageResult(Optional.<String>absent(), false, CoreMessageKey.SIGN_MESSAGE_SIGNING_KEY_NOT_ENCRYPTED, null);
          }
        } else {
          // No signing key found.
          return new SignMessageResult(Optional.<String>absent(), false, CoreMessageKey.SIGN_MESSAGE_NO_SIGNING_KEY, new Object[]{addressText});
        }
      } else {
        return new SignMessageResult(Optional.<String>absent(), false, CoreMessageKey.SIGN_MESSAGE_NO_WALLET, null);
      }
    } catch (KeyCrypterException e) {
      return new SignMessageResult(Optional.<String>absent(), false, CoreMessageKey.SIGN_MESSAGE_NO_PASSWORD, null);
    } catch (RuntimeException | AddressFormatException e) {
      log.error("Sign message failure", e);
      return new SignMessageResult(Optional.<String>absent(), false, CoreMessageKey.SIGN_MESSAGE_FAILURE, null);
    }
  }

  /**
   * <p>Method to verify a message</p>
   *
   * @param addressText   Text address to use to sign (makes UI Address conversion code DRY)
   * @param messageText   The message to sign
   * @param signatureText The signature text (can include CRLF characters which will be stripped)
   *
   * @return A "verify message result" describing the outcome
   */
  public VerifyMessageResult verifyMessage(String addressText, String messageText, String signatureText) {
    if (Strings.isNullOrEmpty(addressText)) {
      return new VerifyMessageResult(false, CoreMessageKey.VERIFY_MESSAGE_ENTER_ADDRESS, null);
    }

    if (Strings.isNullOrEmpty(messageText)) {
      return new VerifyMessageResult(false, CoreMessageKey.VERIFY_MESSAGE_ENTER_MESSAGE, null);
    }

    if (Strings.isNullOrEmpty(signatureText)) {
      return new VerifyMessageResult(false, CoreMessageKey.VERIFY_MESSAGE_ENTER_SIGNATURE, null);
    }

    try {
      Address signingAddress = new Address(BitcoinNetwork.current().get(), addressText);

      // Strip CRLF from signature text
      signatureText = signatureText.replaceAll("\n", "").replaceAll("\r", "");

      ECKey key = ECKey.signedMessageToKey(messageText, signatureText);
      Address gotAddress = key.toAddress(BitcoinNetwork.current().get());
      if (signingAddress.equals(gotAddress)) {
        return new VerifyMessageResult(true, CoreMessageKey.VERIFY_MESSAGE_VERIFY_SUCCESS, null);
      } else {
        return new VerifyMessageResult(false, CoreMessageKey.VERIFY_MESSAGE_VERIFY_FAILURE, null);
      }

    } catch (RuntimeException | AddressFormatException | SignatureException e) {
      log.warn("Failed to verify the message", e.getClass().getCanonicalName() + " " + e.getMessage());
      return new VerifyMessageResult(false, CoreMessageKey.VERIFY_MESSAGE_FAILURE, null);
    }
  }

  /**
   * Password short passwords with extra bytes - this is done so that the existence of short passwords is not leaked by
   * the length of the encrypted credentials (which is always a multiple of the AES block size (16 bytes).
   *
   * @param passwordBytes the credentials bytes to pad
   *
   * @return paddedPasswordBytes - this is guaranteed to be longer than 48 bytes. Byte 0 indicates the number of padding bytes,
   * which are random bytes stored from byte 1 to byte <number of padding bytes). The real credentials is stored int he remaining bytes
   */
  public static byte[] padPasswordBytes(byte[] passwordBytes) {
    if (passwordBytes.length > AESUtils.BLOCK_LENGTH * 3) {
      // No padding required - add a zero to the beginning of the credentials bytes (to indicate no padding bytes)
      return Bytes.concat(new byte[]{(byte) 0x0}, passwordBytes);
    } else {
      if (passwordBytes.length > AESUtils.BLOCK_LENGTH * 2) {
        // Pad with 16 random bytes
        byte[] paddingBytes = new byte[16];
        random.nextBytes(paddingBytes);
        return Bytes.concat(new byte[]{(byte) 0x10}, paddingBytes, passwordBytes);
      } else {
        if (passwordBytes.length > AESUtils.BLOCK_LENGTH) {
          // Pad with 32 random bytes
          byte[] paddingBytes = new byte[32];
          random.nextBytes(paddingBytes);
          return Bytes.concat(new byte[]{(byte) 0x20}, paddingBytes, passwordBytes);
        } else {
          // Pad with 48 random bytes
          byte[] paddingBytes = new byte[48];
          random.nextBytes(paddingBytes);
          return Bytes.concat(new byte[]{(byte) 0x30}, paddingBytes, passwordBytes);
        }
      }
    }
  }

  /**
   * Unpad credentials bytes, removing the random prefix bytes length marker byte and te random bytes themselves
   */
  public static byte[] unpadPasswordBytes(byte[] paddedPasswordBytes) {
    Preconditions.checkNotNull(paddedPasswordBytes);
    Preconditions.checkState(paddedPasswordBytes.length > 0);

    // Get the length of the pad
    int lengthOfPad = (int) paddedPasswordBytes[0];

    if (lengthOfPad > paddedPasswordBytes.length - 1) {
      throw new IllegalStateException("Stored encrypted credentials is not in the correct format");
    }
    return Arrays.copyOfRange(paddedPasswordBytes, 1 + lengthOfPad, paddedPasswordBytes.length);
  }

  /**
   * Generate the DeterministicKey from the private master key for a Trezor  wallet
   * <p/>
   * For a real Trezor device this will be the result of a GetPublicKey of the M/44'/0'/0' path, received as an xpub and then converted to a DeterministicKey
   *
   * @param privateMasterKey the private master key derived from the wallet seed
   *
   * @return the public only DeterministicSeed corresponding to the root Trezor wallet node e.g. M/44'/0'/0'
   */
  public static DeterministicKey generateTrezorWalletRootNode(DeterministicKey privateMasterKey) {
    DeterministicKey key_m_44h = HDKeyDerivation.deriveChildKey(privateMasterKey, new ChildNumber(44 | ChildNumber.HARDENED_BIT));
    log.debug("key_m_44h deterministic key = " + key_m_44h);

    DeterministicKey key_m_44h_0h = HDKeyDerivation.deriveChildKey(key_m_44h, ChildNumber.ZERO_HARDENED);
    log.debug("key_m_44h_0h deterministic key = " + key_m_44h_0h);

    DeterministicKey key_m_44h_0h_0h = HDKeyDerivation.deriveChildKey(key_m_44h_0h, ChildNumber.ZERO_HARDENED);
    log.debug("key_m_44h_0h_0h = " + key_m_44h_0h_0h);

    return key_m_44h_0h_0h;
  }

  /**
   * @param shutdownType The shutdown type
   */
  public void shutdownNow(ShutdownEvent.ShutdownType shutdownType) {

    log.debug("Received shutdown: {}", shutdownType.name());

    // Writing out a wallet to a clear text file is security risk
    // Do not do it except for debug
    // log.debug("Wallet at shutdown:\n{}\n", getCurrentWalletSummary().isPresent() ? getCurrentWalletSummary().get().getWallet() : "");
    currentWalletSummary = Optional.absent();

  }

  /**
   * <p>Save the current wallet to application directory, create a rolling backup and a cloud backup</p>
   */
  public void saveWallet() {

    // Save the current wallet immediately
    if (getCurrentWalletSummary().isPresent()) {

      WalletSummary walletSummary = WalletManager.INSTANCE.getCurrentWalletSummary().get();
      WalletId walletId = walletSummary.getWalletId();
      log.debug("Saving wallet with id : {}, height : {}", walletId, walletSummary.getWallet().getLastBlockSeenHeight());

      // Check that the password is the correct password for this wallet
      if (!walletId.equals(walletSummary.getWalletPassword().getWalletId())) {
        throw new WalletSaveException("The password specified is not the password for this wallet");
      }

      try {
        File applicationDataDirectory = InstallationManager.getOrCreateApplicationDataDirectory();
        File currentWalletFile = WalletManager.INSTANCE.getCurrentWalletFile(applicationDataDirectory).get();

        walletSummary.getWallet().saveToFile(currentWalletFile);

        File encryptedAESCopy = EncryptedFileReaderWriter.makeAESEncryptedCopyAndDeleteOriginal(currentWalletFile, walletSummary.getWalletPassword().getPassword());
        if (encryptedAESCopy == null) {
          log.debug("Did not create AES encrypted wallet");
        } else {
          log.debug("Created AES encrypted wallet as file:\n'{}'\nSize: {} bytes", encryptedAESCopy.getAbsolutePath(), encryptedAESCopy.length());
        }
        BackupService backupService = CoreServices.getOrCreateBackupService();
        backupService.rememberWalletSummaryAndPasswordForRollingBackup(walletSummary, walletSummary.getWalletPassword().getPassword());
        backupService.rememberWalletIdAndPasswordForLocalZipBackup(walletSummary.getWalletId(), walletSummary.getWalletPassword().getPassword());
        backupService.rememberWalletIdAndPasswordForCloudZipBackup(walletSummary.getWalletId(), walletSummary.getWalletPassword().getPassword());

      } catch (IOException ioe) {
        log.error("Could not write wallet and backups for wallet with id '" + walletId + "' successfully. The error was '" + ioe.getMessage() + "'");
      }
    }

  }

  /**
   * Closes the wallet
   */
  public void closeWallet() {

    if (WalletManager.INSTANCE.getCurrentWalletSummary().isPresent()) {
      try {
        Wallet wallet = WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet();
        log.debug("Shutdown wallet autosave at height: {} ", wallet.getLastBlockSeenHeight());
        wallet.shutdownAutosaveAndWait();
      } catch (IllegalStateException ise) {
        // If there is no autosaving set up yet then that is ok
        if (!ise.getMessage().contains("Auto saving not enabled.")) {
          throw ise;
        }
      }
    } else {
      log.info("No current wallet summary to provide wallet");
    }
  }
  public static byte[] generateRandomIv(){
    SecureRandom secureRandom = new SecureRandom();
    byte[] ivBytes = new byte[16];
    secureRandom.nextBytes(ivBytes);
    return ivBytes;
  }
}
