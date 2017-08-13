package info.nightscout.android.medtronic.service;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import info.nightscout.android.R;
import info.nightscout.android.USB.UsbHidDriver;
import info.nightscout.android.medtronic.MainActivity;
import info.nightscout.android.medtronic.MedtronicCnlReader;
import info.nightscout.android.medtronic.exception.ChecksumException;
import info.nightscout.android.medtronic.exception.EncryptionException;
import info.nightscout.android.medtronic.exception.UnexpectedMessageException;
import info.nightscout.android.medtronic.message.MessageUtils;
import info.nightscout.android.model.medtronicNg.ContourNextLinkInfo;
import info.nightscout.android.model.medtronicNg.PumpInfo;
import info.nightscout.android.model.medtronicNg.PumpStatusEvent;
import info.nightscout.android.upload.nightscout.NightscoutUploadReceiver;
import info.nightscout.android.utils.ConfigurationStore;
import info.nightscout.android.utils.DataStore;
import info.nightscout.android.xdrip_plus.XDripPlusUploadReceiver;
import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;

public class MedtronicCnlIntentService extends IntentService {
    public final static int USB_VID = 0x1a79;
    public final static int USB_PID = 0x6210;
    public final static long USB_WARMUP_TIME_MS = 5000L;
    public final static long POLL_PERIOD_MS = 300000L;
    public final static long LOW_BATTERY_POLL_PERIOD_MS = 900000L;
    // Number of additional seconds to wait after the next expected CGM poll, so that we don't interfere with CGM radio comms.
    public final static long POLL_GRACE_PERIOD_MS = 30000L;
    public final static long POLL_PRE_GRACE_PERIOD_MS = 45000L;

    public static final String ICON_WARN = "{ion-alert-circled} ";
    public static final String ICON_BGL = "{ion-waterdrop} ";
    public static final String ICON_USB = "{ion-usb} ";
    public static final String ICON_INFO = "{ion-information_circled} ";
    public static final String ICON_HELP = "{ion-ios-lightbulb} ";
    public static final String ICON_SETTING = "{ion-android-settings} ";
    public static final String ICON_HEART = "{ion-heart} ";
    public static final String ICON_LOW = "{ion-battery_low} ";
    public static final String ICON_FULL = "{ion-battery_full} ";
    public static final String ICON_RESV = "{ion-paintbucket} ";
    public static final String ICON_CGM = "{ion-ios-pulse_strong} ";
    public static final String ICON_MEDICAL = "{ion-ios-medical} ";
    public static final String ICON_BOLUS = "{ion-erlenmeyer-flask} ";
    public static final String ICON_BASAL = "{ion-ios-timer} ";
    public static final String ICON_NOTE = "{ion_android_notifications} ";

    // show warning message after repeated errors
    private final static int ERROR_COMMS_AT = 4;
    private final static int ERROR_CONNECT_AT = 8;
    private final static int ERROR_SIGNAL_AT = 8;
    private final static int ERROR_PUMPLOSTSENSOR_AT = 8;
    private final static int ERROR_PUMPCLOCK_AT = 8;

    private static final String TAG = MedtronicCnlIntentService.class.getSimpleName();

    private UsbHidDriver mHidDevice;
    private Context mContext;
    private NotificationManagerCompat nm;
    private UsbManager mUsbManager;
    private DataStore dataStore = DataStore.getInstance();
    private ConfigurationStore configurationStore = ConfigurationStore.getInstance();
    private DateFormat dateFormatter = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private DateFormat dateFormatterNote = new SimpleDateFormat("HH:mm", Locale.US);
    private Realm realm;
    private long pumpOffset;

    public MedtronicCnlIntentService() {
        super(MedtronicCnlIntentService.class.getName());
    }

    protected void sendStatus(String message) {
        Intent localIntent =
                new Intent(Constants.ACTION_STATUS_MESSAGE)
                        .putExtra(Constants.EXTENDED_DATA, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);
    }

    protected void sendMessage(String action) {
        Intent localIntent =
                new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "onCreate called");
        mContext = this.getBaseContext();
        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "onDestroy called");

        if (nm != null) {
            nm.cancelAll();
            nm = null;
        }

        if (mHidDevice != null) {
            Log.i(TAG, "Closing serial device...");
            mHidDevice.close();
            mHidDevice = null;
        }
    }

/*

Notes on Errors:

CNL-PUMP pairing and registered devices

CNL: paired PUMP: paired UPLOADER: registered = ok
CNL: paired PUMP: paired UPLOADER: unregistered = ok
CNL: paired PUMP: unpaired UPLOADER: registered = "Could not communicate with the pump. Is it nearby?"
CNL: paired PUMP: unpaired UPLOADER: unregistered = "Could not communicate with the pump. Is it nearby?"
CNL: unpaired PUMP: paired UPLOADER: registered = "Timeout communicating with the Contour Next Link."
CNL: unpaired PUMP: paired UPLOADER: unregistered = "Invalid message received for requestLinkKey, Contour Next Link is not paired with pump."
CNL: unpaired PUMP: unpaired UPLOADER: registered = "Timeout communicating with the Contour Next Link."
CNL: unpaired PUMP: unpaired UPLOADER: unregistered = "Invalid message received for requestLinkKey, Contour Next Link is not paired with pump."

*/

    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "onHandleIntent called");
        try {
            final long timePollStarted = System.currentTimeMillis();
            realm = Realm.getDefaultInstance();

            long due = checkPollTime();
            if (due > 0) {
                sendStatus("Please wait: Pump is expecting sensor communication. Poll due in " + ((due - System.currentTimeMillis()) / 1000L) + " seconds");
                MedtronicCnlAlarmManager.setAlarm(due);
                return;
            }

            RealmResults<PumpStatusEvent> pumpresults = realm.where(PumpStatusEvent.class)
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (6 * 60 * 60 * 1000)))
                    .findAllSorted("eventDate", Sort.DESCENDING);
            long pollInterval = configurationStore.getPollInterval();
            if (pumpresults.size() > 0) {
                short pumpBatteryLevel = pumpresults.first().getBatteryPercentage();
                if ((pumpBatteryLevel > 0) && (pumpBatteryLevel <= 25)) {
                    pollInterval = configurationStore.getLowBatteryPollInterval();
                    sendStatus(ICON_WARN + "Warning: pump battery low");
                    if (pollInterval != configurationStore.getPollInterval()) {
                        sendStatus(ICON_SETTING + "Low battery poll interval: " + (pollInterval / 60000) + " minutes");
                    }
                }
            }

            // TODO - throw, don't return
            if (!openUsbDevice())
                return;

            MedtronicCnlReader cnlReader = new MedtronicCnlReader(mHidDevice);

            realm.beginTransaction();

            try {
                sendStatus("Connecting to Contour Next Link");
                Log.d(TAG, "Connecting to Contour Next Link");
                cnlReader.requestDeviceInfo();

                // Is the device already configured?
                ContourNextLinkInfo info = realm
                        .where(ContourNextLinkInfo.class)
                        .equalTo("serialNumber", cnlReader.getStickSerial())
                        .findFirst();

                if (info == null) {
                    info = realm.createObject(ContourNextLinkInfo.class, cnlReader.getStickSerial());
                }

                cnlReader.getPumpSession().setStickSerial(info.getSerialNumber());

                cnlReader.enterControlMode();

                try {
                    cnlReader.enterPassthroughMode();
                    cnlReader.openConnection();

                    cnlReader.requestReadInfo();

                    // always get LinkKey on startup to handle re-paired CNL-PUMP key changes
                    String key = null;
                    if (dataStore.getCommsSuccess() > 0) {
                        key = info.getKey();
                    }

                    if (key == null) {
                        cnlReader.requestLinkKey();

                        info.setKey(MessageUtils.byteArrayToHexString(cnlReader.getPumpSession().getKey()));
                        key = info.getKey();
                    }

                    cnlReader.getPumpSession().setKey(MessageUtils.hexStringToByteArray(key));

                    long pumpMAC = cnlReader.getPumpSession().getPumpMAC();
                    Log.i(TAG, "PumpInfo MAC: " + (pumpMAC & 0xffffff));
                    PumpInfo activePump = realm
                            .where(PumpInfo.class)
                            .equalTo("pumpMac", pumpMAC)
                            .findFirst();

                    if (activePump == null) {
                        activePump = realm.createObject(PumpInfo.class, pumpMAC);
                    }

                    activePump.updateLastQueryTS();

                    byte radioChannel = cnlReader.negotiateChannel(activePump.getLastRadioChannel());
                    if (radioChannel == 0) {
                        sendStatus(ICON_WARN + "Could not communicate with the pump. Is it nearby?");
                        Log.i(TAG, "Could not communicate with the pump. Is it nearby?");
                        dataStore.incCommsConnectError();
                        pollInterval = configurationStore.getPollInterval() / (configurationStore.isReducePollOnPumpAway() ? 2L : 1L); // reduce polling interval to half until pump is available
                    } else if (cnlReader.getPumpSession().getRadioRSSIpercentage() < 5) {
                        sendStatus(String.format(Locale.getDefault(), "Connected on channel %d  RSSI: %d%%", (int) radioChannel, cnlReader.getPumpSession().getRadioRSSIpercentage()));
                        sendStatus(ICON_WARN + "Warning: pump signal too weak. Is it nearby?");
                        Log.i(TAG, "Warning: pump signal too weak. Is it nearby?");
                        dataStore.incCommsConnectError();
                        dataStore.incCommsSignalError();
                        pollInterval = configurationStore.getPollInterval() / (configurationStore.isReducePollOnPumpAway() ? 2L : 1L); // reduce polling interval to half until pump is available
                    } else {
                        dataStore.decCommsConnectError();
                        if (cnlReader.getPumpSession().getRadioRSSIpercentage() < 20)
                            dataStore.incCommsSignalError();
                        else
                            dataStore.decCommsSignalError();

                        dataStore.setActivePumpMac(pumpMAC);

                        activePump.setLastRadioChannel(radioChannel);
                        sendStatus(String.format(Locale.getDefault(), "Connected on channel %d  RSSI: %d%%", (int) radioChannel, cnlReader.getPumpSession().getRadioRSSIpercentage()));
                        Log.d(TAG, String.format("Connected to Contour Next Link on channel %d.", (int) radioChannel));

                        // read pump status
                        PumpStatusEvent pumpRecord = realm.createObject(PumpStatusEvent.class);

                        String deviceName = String.format("medtronic-600://%s", cnlReader.getStickSerial());
                        activePump.setDeviceName(deviceName);

                        // TODO - this should not be necessary. We should reverse lookup the device name from PumpInfo
                        pumpRecord.setDeviceName(deviceName);

                        long pumpTime = cnlReader.getPumpTime().getTime();
                        pumpOffset = pumpTime - System.currentTimeMillis();
                        Log.d(TAG, "Time offset between pump and device: " + pumpOffset + " millis.");

                        pumpRecord.setPumpTimeOffset(pumpOffset);
                        pumpRecord.setPumpDate(new Date(pumpTime));
                        cnlReader.updatePumpStatus(pumpRecord);

                        validatePumpRecord(pumpRecord, activePump);
                        activePump.getPumpHistory().add(pumpRecord);
                        realm.commitTransaction();

                        dataStore.incCommsSuccess();
                        dataStore.clearCommsError();

                        if (pumpRecord.isCgmActive()) {
                            dataStore.clearPumpCgmNA(); // poll clash detection
                            dataStore.clearPumpLostSensorError();

                            if (pumpRecord.isCgmWarmUp())
                                sendStatus(ICON_CGM + "sensor is in warm-up phase");
                            else if (pumpRecord.getCalibrationDueMinutes() == 0)
                                sendStatus(ICON_CGM + "sensor calibration is due now!");
                            else if (pumpRecord.getSgv() == 0)
                                sendStatus(ICON_CGM + "sensor error (pump graph gap)");
                            else {
                                dataStore.incCommsSgvSuccess();
                                sendStatus("SGV: " + MainActivity.strFormatSGV(pumpRecord.getSgv())
                                        + "  At: " + dateFormatter.format(pumpRecord.getCgmDate().getTime())
                                        + "  Pump: " + (pumpOffset > 0 ? "+" : "") + (pumpOffset / 1000L) + "sec");
                                if (pumpRecord.isOldSgvWhenNewExpected()) {
                                    sendStatus(ICON_WARN + "Pump sent old SGV event");
                                    // pump may have missed sensor transmission or be delayed in posting to status message
                                    // in most cases the next scheduled poll will have latest sgv, occasionally it is available this period after a delay
                                    // if user selects double poll option we try again this period or wait until next
                                    pollInterval = POLL_PERIOD_MS / (configurationStore.isReducePollOnPumpAway() ? 2L : 1L);
                                }
                            }

                        } else {
                            sendStatus(ICON_CGM + "cgm n/a (pump lost sensor)");
                            dataStore.incPumpCgmNA(); // poll clash detection
                            if (dataStore.getCommsSgvSuccess() > 0) // only count errors if cgm is being used
                                dataStore.incPumpLostSensorError();
                        }

                        sendStatusTreatments(pumpRecord);

                        // Tell the Main Activity we have new data
                        sendMessage(Constants.ACTION_UPDATE_PUMP);
                    }

                } catch (UnexpectedMessageException e) {
                    dataStore.incCommsError();
                    pollInterval = 60000L; // retry once during this poll period, this allows for transient radio noise
                    Log.e(TAG, "Unexpected Message", e);
                    sendStatus(ICON_WARN + "Communication Error: " + e.getMessage());
                } catch (TimeoutException e) {
                    dataStore.incCommsError();
                    pollInterval = 90000L; // retry once during this poll period, this allows for transient radio noise
                    Log.e(TAG, "Timeout communicating with the Contour Next Link.", e);
                    sendStatus(ICON_WARN + "Timeout communicating with the Contour Next Link / Pump.");
                } catch (NoSuchAlgorithmException e) {
                    Log.e(TAG, "Could not determine CNL HMAC", e);
                    sendStatus(ICON_WARN + "Error connecting to Contour Next Link: Hashing error.");
                } finally {
                    try {
                        cnlReader.closeConnection();
                        cnlReader.endPassthroughMode();
                        cnlReader.endControlMode();
                    } catch (NoSuchAlgorithmException e) {}
                }
            } catch (IOException e) {
                dataStore.incCommsError();
                Log.e(TAG, "Error connecting to Contour Next Link.", e);
                sendStatus(ICON_WARN + "Error connecting to Contour Next Link.");
            } catch (ChecksumException e) {
                dataStore.incCommsError();
                Log.e(TAG, "Checksum error getting message from the Contour Next Link.", e);
                sendStatus(ICON_WARN + "Checksum error getting message from the Contour Next Link.");
            } catch (EncryptionException e) {
                dataStore.incCommsError();
                Log.e(TAG, "Error decrypting messages from Contour Next Link.", e);
                sendStatus(ICON_WARN + "Error decrypting messages from Contour Next Link.");
            } catch (TimeoutException e) {
                dataStore.incCommsError();
                Log.e(TAG, "Timeout communicating with the Contour Next Link.", e);
                sendStatus(ICON_WARN + "Timeout communicating with the Contour Next Link.");
            } catch (UnexpectedMessageException e) {
                dataStore.incCommsError();
                Log.e(TAG, "Could not close connection.", e);
                sendStatus(ICON_WARN + "Could not close connection: " + e.getMessage());
            } finally {

                if (!realm.isClosed()) {
                    if (realm.isInTransaction()) {
                        // If we didn't commit the transaction, we've run into an error. Let's roll it back
                        realm.cancelTransaction();
                    }
                    RemoveOutdatedRecords();

                    long nextpoll = requestPollTime(timePollStarted, pollInterval);
                    MedtronicCnlAlarmManager.setAlarm(nextpoll);
                    sendStatus("Next poll due at: " + dateFormatter.format(nextpoll));

                    realm.close();
                }

                uploadPollResults();
                sendStatusWarnings();
            }

        } finally {
            MedtronicCnlAlarmReceiver.completeWakefulIntent(intent);
        }
    }

    private void sendStatusTreatments(PumpStatusEvent pumpRecord) {
        if (pumpRecord.isValidBGL())
            sendStatus(ICON_BGL + "Recent finger BG: " + MainActivity.strFormatSGV(pumpRecord.getRecentBGL()));

        if (pumpRecord.isValidBolus()) {
            if (pumpRecord.isValidBolusSquare())
                sendStatus(ICON_BOLUS + "Square bolus delivered: " + pumpRecord.getLastBolusAmount() + "u Started: " + dateFormatter.format(pumpRecord.getLastBolusDate()) + " Duration: " + pumpRecord.getLastBolusDuration() + " minutes");
            else if (pumpRecord.isValidBolusDual())
                sendStatus(ICON_BOLUS + "Bolus (dual normal part): " + pumpRecord.getLastBolusAmount() + "u At: " + dateFormatter.format(pumpRecord.getLastBolusDate()));
            else
                sendStatus(ICON_BOLUS + "Bolus: " + pumpRecord.getLastBolusAmount() + "u At: " + dateFormatter.format(pumpRecord.getLastBolusDate()));
        }

        if (pumpRecord.isValidTEMPBASAL()) {
            if (pumpRecord.getTempBasalMinutesRemaining() > 0 && pumpRecord.getTempBasalPercentage() > 0)
                sendStatus(ICON_BASAL + "Temp basal: " + pumpRecord.getTempBasalPercentage() + "% Remaining: " + pumpRecord.getTempBasalMinutesRemaining() + " minutes");
            else if (pumpRecord.getTempBasalMinutesRemaining() > 0)
                sendStatus(ICON_BASAL + "Temp basal: " + pumpRecord.getTempBasalRate() + "u Remaining: " + pumpRecord.getTempBasalMinutesRemaining() + " minutes");
            else
                sendStatus(ICON_BASAL + "Temp basal stopped");
        }

        if (pumpRecord.isValidSAGE())
            sendStatus(ICON_NOTE + "Sensor changed approx: " + dateFormatterNote.format(pumpRecord.getSageAfterDate()) + " - " + dateFormatterNote.format(pumpRecord.getSageBeforeDate()));
        if (pumpRecord.isValidCAGE())
            sendStatus(ICON_NOTE + "Reservoir changed approx: " + dateFormatterNote.format(pumpRecord.getCageAfterDate()) + " - " + dateFormatterNote.format(pumpRecord.getCageBeforeDate()));
        if (pumpRecord.isValidBATTERY())
            sendStatus(ICON_NOTE + "Pump battery changed approx: " + dateFormatterNote.format(pumpRecord.getBatteryAfterDate()) + " - " + dateFormatterNote.format(pumpRecord.getBatteryBeforeDate()));
    }

    private void sendStatusWarnings() {

        if (Math.abs(pumpOffset) > 10 * 60 * 1000)
            dataStore.incPumpClockError();
        if (dataStore.getPumpClockError() >= ERROR_PUMPCLOCK_AT) {
            dataStore.clearPumpClockError();
            sendStatus(ICON_WARN + "Warning: Time difference between Pump and Uploader excessive."
                    + " Pump is over " + (Math.abs(pumpOffset) / 60000L) + " minutes " + (pumpOffset > 0 ? "ahead" : "behind") + " of time used by uploader.");
            sendStatus(ICON_HELP + "The uploader phone/device should have the current time provided by network. Pump clock drifts forward and needs to be set to correct time occasionally.");
        }
        if (dataStore.getCommsError() >= ERROR_COMMS_AT) {
            sendStatus(ICON_WARN + "Warning: multiple comms/timeout errors detected.");
            sendStatus(ICON_HELP + "Try: disconnecting and reconnecting the Contour Next Link to phone / restarting phone / check pairing of CNL with Pump.");
        }
        if (dataStore.getPumpLostSensorError() >= ERROR_PUMPLOSTSENSOR_AT) {
            dataStore.clearPumpLostSensorError();
            sendStatus(ICON_WARN + "Warning: SGV is unavailable from pump often. The pump is missing transmissions from the sensor.");
            sendStatus(ICON_HELP + "Keep pump on same side of body as sensor. Avoid using body sensor locations that can block radio signal.");
        }
        if (dataStore.getCommsConnectError() >= ERROR_CONNECT_AT * (configurationStore.isReducePollOnPumpAway() ? 2 : 1)) {
            dataStore.clearCommsConnectError();
            sendStatus(ICON_WARN + "Warning: connecting to pump is failing often.");
            sendStatus(ICON_HELP + "Keep pump nearby to uploader phone/device. The body can block radio signals between pump and uploader.");
        }
        if (dataStore.getCommsSignalError() >= ERROR_SIGNAL_AT) {
            dataStore.clearCommsSignalError();
            sendStatus(ICON_WARN + "Warning: RSSI radio signal from pump is generally weak and may increase errors.");
            sendStatus(ICON_HELP + "Keep pump nearby to uploader phone/device. The body can block radio signals between pump and uploader.");
        }
    }

    private void RemoveOutdatedRecords() {
        final RealmResults<PumpStatusEvent> resultsx =
                realm.where(PumpStatusEvent.class)
                        .lessThan("eventDate", new Date(System.currentTimeMillis() - (48 * 60 * 60 * 1000)))
                        .findAll();

        if (resultsx.size() > 0) {
            realm.executeTransaction(new Realm.Transaction() {
                @Override
                public void execute(Realm realm) {
                    // Delete all matches
                    Log.d(TAG, "Deleting " + resultsx.size() + " records from realm");
                    resultsx.deleteAllFromRealm();
                }
            });
        }
    }

// TODO fix square/dual bolus time after battery change as pump resets start time, use refs to work out actual time and duration?
// TODO for NS add temp basal at 0% when suspended? use 30 or 60 min blocks and cancel temp when resumed?
// TODO check on optimal use of Realm search+results as we make heavy use for validation

    private void validatePumpRecord(PumpStatusEvent pumpRecord, PumpInfo activePump) {

        // TODO cgm/sgv validation - handle sensor exceptions
        // validate that this contains a new CGM record
        if (pumpRecord.isCgmActive()) {
            pumpRecord.setValidCGM(true);
        }

        // validate that this contains a new SGV record
        if (pumpRecord.isCgmActive()) {
            if (pumpRecord.getPumpDate().getTime() - pumpRecord.getCgmPumpDate().getTime() > POLL_PERIOD_MS + (POLL_GRACE_PERIOD_MS / 2))
                pumpRecord.setOldSgvWhenNewExpected(true);
            else if (!pumpRecord.isCgmWarmUp() && pumpRecord.getSgv() > 0) {
                RealmResults<PumpStatusEvent> sgv_results = activePump.getPumpHistory()
                        .where()
                        .equalTo("cgmPumpDate", pumpRecord.getCgmPumpDate())
                        .equalTo("validSGV", true)
                        .findAll();
                if (sgv_results.size() == 0)
                    pumpRecord.setValidSGV(true);
            }
        }

        // validate that this contains a new BGL record
        if (pumpRecord.getRecentBGL() != 0) {
            RealmResults<PumpStatusEvent> bgl_results = activePump.getPumpHistory()
                    .where()
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (20 * 60 * 1000)))
                    .equalTo("recentBGL", pumpRecord.getRecentBGL())
                    .findAll();
            if (bgl_results.size() == 0) {
                pumpRecord.setValidBGL(true);
            }
        }

        // validate that this contains a new BOLUS record
        RealmResults<PumpStatusEvent> lastbolus_results = activePump.getPumpHistory()
                .where()
                .equalTo("lastBolusPumpDate", pumpRecord.getLastBolusPumpDate())
                .equalTo("lastBolusReference", pumpRecord.getLastBolusReference())
                .equalTo("validBolus", true)
                .findAll();

        if (lastbolus_results.size() == 0) {
            pumpRecord.setValidBolus(true);

            if (pumpRecord.getBolusingReference() == pumpRecord.getLastBolusReference()
                    && pumpRecord.getBolusingMinutesRemaining() > 10) {
                pumpRecord.setValidBolusDual(true);
            }

            RealmResults<PumpStatusEvent> bolusing_results = activePump.getPumpHistory()
                    .where()
                    .equalTo("bolusingReference", pumpRecord.getLastBolusReference())
                    .greaterThan("bolusingMinutesRemaining", 10)
                    .findAllSorted("eventDate", Sort.ASCENDING);

            // note: if pump battery is changed during square/dual bolus period the last bolus time will be set to this time (pump asks user to resume/cancel bolus)

            if (bolusing_results.size() > 0) {
                pumpRecord.setValidBolusSquare(true);
                long start = pumpRecord.getLastBolusPumpDate().getTime();
                long end = pumpRecord.getPumpDate().getTime();
                long duration = bolusing_results.first().getPumpDate().getTime() - start + (bolusing_results.first().getBolusingMinutesRemaining() * 60000);
                if (start + duration > end) // was square bolus stopped before expected duration?
                    duration = end - start;
                pumpRecord.setLastBolusDuration((short) (duration / 60000));
            }
        }

        // validate that this contains a new TEMP BASAL record
        // temp basal: rate / percentage can be set on pump for max duration of 24 hours / 1440 minutes
        RealmResults<PumpStatusEvent> tempbasal_results = activePump.getPumpHistory()
                .where()
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (24 * 60 * 60 * 1000)))
                .findAllSorted("eventDate", Sort.DESCENDING);
        if (pumpRecord.getTempBasalMinutesRemaining() > 0) {
            int index = 0;
            if (tempbasal_results.size() > 1) {
                short minutes = pumpRecord.getTempBasalMinutesRemaining();
                for (index = 0; index < tempbasal_results.size(); index++) {
                    if (tempbasal_results.get(index).getTempBasalMinutesRemaining() < minutes ||
                            tempbasal_results.get(index).getTempBasalPercentage() != pumpRecord.getTempBasalPercentage() ||
                            tempbasal_results.get(index).getTempBasalRate() != pumpRecord.getTempBasalRate() ||
                            tempbasal_results.get(index).isValidTEMPBASAL())
                        break;
                    minutes = tempbasal_results.get(index).getTempBasalMinutesRemaining();
                }
            }
            if (tempbasal_results.size() > 0)
                if (!tempbasal_results.get(index).isValidTEMPBASAL() ||
                        tempbasal_results.get(index).getTempBasalPercentage() != pumpRecord.getTempBasalPercentage() ||
                        tempbasal_results.get(index).getTempBasalRate() != pumpRecord.getTempBasalRate()) {
                    pumpRecord.setValidTEMPBASAL(true);
                    pumpRecord.setTempBasalAfterDate(tempbasal_results.get(index).getEventDate());
                }
        } else {
            // check if stopped before expected duration
            if (tempbasal_results.size() > 0)
                if (pumpRecord.getPumpDate().getTime() - tempbasal_results.first().getPumpDate().getTime() - (tempbasal_results.first().getTempBasalMinutesRemaining() * 60 * 1000) < -60 * 1000) {
                    pumpRecord.setValidTEMPBASAL(true);
                    pumpRecord.setTempBasalAfterDate(tempbasal_results.first().getEventDate());
                }
        }

        // validate that this contains a new SAGE record
        if (pumpRecord.isCgmWarmUp()) {
            RealmResults<PumpStatusEvent> sage_results = activePump.getPumpHistory()
                    .where()
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (130 * 60 * 1000)))
                    .equalTo("validSAGE", true)
                    .findAll();
            if (sage_results.size() == 0) {
                pumpRecord.setValidSAGE(true);
                RealmResults<PumpStatusEvent> sagedate_results = activePump.getPumpHistory()
                        .where()
                        .greaterThan("eventDate", new Date(System.currentTimeMillis() - (12 * 60 * 1000)))
                        .findAllSorted("eventDate", Sort.DESCENDING);
                pumpRecord.setSageAfterDate(sagedate_results.first().getEventDate());
                pumpRecord.setSageBeforeDate(pumpRecord.getEventDate());
            }
        } else if (pumpRecord.isCgmActive() && pumpRecord.getTransmitterBattery() > 70) {
            // note: transmitter battery can fluctuate when on the edge of a state change, usually low battery
            RealmResults<PumpStatusEvent> sagebattery_results = activePump.getPumpHistory()
                    .where()
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (12 * 60 * 60 * 1000)))
                    .equalTo("cgmActive", true)
                    .lessThan("transmitterBattery", pumpRecord.getTransmitterBattery())
                    .findAllSorted("eventDate", Sort.DESCENDING);
            if (sagebattery_results.size() > 0) {
                RealmResults<PumpStatusEvent> sage_valid_results = activePump.getPumpHistory()
                        .where()
                        .greaterThanOrEqualTo("eventDate", sagebattery_results.first().getEventDate())
                        .equalTo("validSAGE", true)
                        .findAll();
                if (sage_valid_results.size() == 0) {
                    pumpRecord.setValidSAGE(true);
                    pumpRecord.setSageAfterDate(sagebattery_results.first().getEventDate());
                    pumpRecord.setSageBeforeDate(pumpRecord.getEventDate());
                }
            }
        }

        // validate that this contains a new CAGE record
        RealmResults<PumpStatusEvent> cage_results = activePump.getPumpHistory()
                .where()
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (12 * 60 * 60 * 1000)))
                .lessThan("reservoirAmount", pumpRecord.getReservoirAmount())
                .findAllSorted("eventDate", Sort.DESCENDING);
        if (cage_results.size() > 0) {
            RealmResults<PumpStatusEvent> cage_valid_results = activePump.getPumpHistory()
                    .where()
                    .greaterThanOrEqualTo("eventDate", cage_results.first().getEventDate())
                    .equalTo("validCAGE", true)
                    .findAll();
            if (cage_valid_results.size() == 0) {
                pumpRecord.setValidCAGE(true);
                pumpRecord.setCageAfterDate(cage_results.first().getEventDate());
                pumpRecord.setCageBeforeDate(pumpRecord.getEventDate());
            }
        }

        // validate that this contains a new BATTERY record
        RealmResults<PumpStatusEvent> battery_results = activePump.getPumpHistory()
                .where()
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (12 * 60 * 60 * 1000)))
                .lessThan("batteryPercentage", pumpRecord.getBatteryPercentage())
                .findAllSorted("eventDate", Sort.DESCENDING);
        if (battery_results.size() > 0) {
            RealmResults<PumpStatusEvent> battery_valid_results = activePump.getPumpHistory()
                    .where()
                    .greaterThanOrEqualTo("eventDate", battery_results.first().getEventDate())
                    .equalTo("validBATTERY", true)
                    .findAll();
            if (battery_valid_results.size() == 0) {
                pumpRecord.setValidBATTERY(true);
                pumpRecord.setBatteryAfterDate(battery_results.first().getEventDate());
                pumpRecord.setBatteryBeforeDate(pumpRecord.getEventDate());
            }
        }

    }

    // pollInterval: default = POLL_PERIOD_MS (time to pump cgm reading)
    //
    // Can be requested at a shorter or longer interval, used to request a retry before next expected cgm data or to extend poll times due to low pump battery.
    // Requests the next poll based on the actual time last cgm data was available on the pump and adding the interval
    // if this time is already stale then the next actual cgm time will be used
    // Any poll time request that falls within the pre-grace/grace period will be pushed to the next safe time slot

    private long requestPollTime(long lastPoll, long pollInterval) {

        int pumpCgmNA = dataStore.getPumpCgmNA();

        RealmResults<PumpStatusEvent> cgmresults = realm.where(PumpStatusEvent.class)
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (24 * 60 * 1000)))
                .equalTo("validCGM", true)
                .findAllSorted("cgmDate", Sort.DESCENDING);
        long timeLastCGM = 0;
        if (cgmresults.size() > 0) {
            timeLastCGM = cgmresults.first().getCgmDate().getTime();
        }

        long now = System.currentTimeMillis();
        long lastActualPollTime = lastPoll;
        if (timeLastCGM > 0)
            lastActualPollTime = timeLastCGM + POLL_GRACE_PERIOD_MS + (POLL_PERIOD_MS * ((now - timeLastCGM + POLL_GRACE_PERIOD_MS) / POLL_PERIOD_MS));
        long nextActualPollTime = lastActualPollTime + POLL_PERIOD_MS;
        long nextRequestedPollTime = lastActualPollTime + pollInterval;

        // check if requested poll is stale
        if (nextRequestedPollTime - now < 10 * 1000)
            nextRequestedPollTime = nextActualPollTime;

        // extended unavailable cgm may be due to clash with the current polling time
        // while we wait for a cgm event, polling is auto adjusted by offsetting the next poll based on miss count

        if (timeLastCGM == 0)
            nextRequestedPollTime += 15 * 1000; // push poll time forward to avoid potential clash when no previous poll time available to sync with
        else if (pumpCgmNA > 2)
            nextRequestedPollTime += ((pumpCgmNA - 2) % 3) * 30 * 1000; // adjust poll time in 30 second steps to avoid potential poll clash (adjustment: poll+30s / poll+60s / poll+0s)

        // check if requested poll time is too close to next actual poll time
        if (nextRequestedPollTime > nextActualPollTime - POLL_GRACE_PERIOD_MS - POLL_PRE_GRACE_PERIOD_MS
                && nextRequestedPollTime < nextActualPollTime) {
            nextRequestedPollTime = nextActualPollTime;
        }

        return nextRequestedPollTime;
    }

    private long checkPollTime() {
        long due = 0;

        RealmResults<PumpStatusEvent> cgmresults = realm.where(PumpStatusEvent.class)
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (24 * 60 * 1000)))
                .equalTo("validCGM", true)
                .findAllSorted("cgmDate", Sort.DESCENDING);

        if (cgmresults.size() > 0) {
            long now = System.currentTimeMillis();
            long timeLastCGM = cgmresults.first().getCgmDate().getTime();
            long timePollExpected = timeLastCGM + POLL_PERIOD_MS + POLL_GRACE_PERIOD_MS + (POLL_PERIOD_MS * ((now - 1000L - (timeLastCGM + POLL_GRACE_PERIOD_MS)) / POLL_PERIOD_MS));
            // avoid polling when too close to sensor-pump comms
            if (((timePollExpected - now) > 5000L) && ((timePollExpected - now) < (POLL_PRE_GRACE_PERIOD_MS + POLL_GRACE_PERIOD_MS)))
                due = timePollExpected;
        }

        return due;
    }

    /**
     * @return if device acquisition was successful
     */
    private boolean openUsbDevice() {
        if (!hasUsbHostFeature()) {
            sendStatus(ICON_WARN + "It appears that this device doesn't support USB OTG.");
            Log.e(TAG, "Device does not support USB OTG");
            return false;
        }

        UsbDevice cnlStick = UsbHidDriver.getUsbDevice(mUsbManager, USB_VID, USB_PID);
        if (cnlStick == null) {
            sendStatus(ICON_WARN + "USB connection error. Is the Contour Next Link plugged in?");
            Log.w(TAG, "USB connection error. Is the CNL plugged in?");
            return false;
        }

        if (!mUsbManager.hasPermission(UsbHidDriver.getUsbDevice(mUsbManager, USB_VID, USB_PID))) {
            sendMessage(Constants.ACTION_NO_USB_PERMISSION);
            return false;
        }
        mHidDevice = UsbHidDriver.acquire(mUsbManager, cnlStick);

        try {
            mHidDevice.open();
        } catch (Exception e) {
            sendStatus(ICON_WARN + "Unable to open USB device");
            Log.e(TAG, "Unable to open serial device", e);
            return false;
        }

        return true;
    }

    // reliable wake alarm manager wake up for all android versions
    public static void wakeUpIntent(Context context, long wakeTime, PendingIntent pendingIntent) {
        final AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarm.setExact(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
        } else
            alarm.set(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
    }

    private void uploadPollResults() {
        sendToXDrip();
        uploadToNightscout();
    }

    private void sendToXDrip() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (prefs.getBoolean(getString(R.string.preference_enable_xdrip_plus), false)) {
            final Intent receiverIntent = new Intent(this, XDripPlusUploadReceiver.class);
            final long timestamp = System.currentTimeMillis() + 500L;
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, (int) timestamp, receiverIntent, PendingIntent.FLAG_ONE_SHOT);
            Log.d(TAG, "Scheduling xDrip+ send");
            wakeUpIntent(getApplicationContext(), timestamp, pendingIntent);
        }
    }

    private void uploadToNightscout() {
        // TODO - set status if offline or Nightscout not reachable
        Intent receiverIntent = new Intent(this, NightscoutUploadReceiver.class);
        final long timestamp = System.currentTimeMillis() + 1000L;
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, (int) timestamp, receiverIntent, PendingIntent.FLAG_ONE_SHOT);
        wakeUpIntent(getApplicationContext(), timestamp, pendingIntent);
    }

    private boolean hasUsbHostFeature() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
    }

    public final class Constants {
        public static final String ACTION_STATUS_MESSAGE = "info.nightscout.android.medtronic.service.STATUS_MESSAGE";
        public static final String ACTION_NO_USB_PERMISSION = "info.nightscout.android.medtronic.service.NO_USB_PERMISSION";
        public static final String ACTION_USB_PERMISSION = "info.nightscout.android.medtronic.USB_PERMISSION";
        public static final String ACTION_USB_REGISTER = "info.nightscout.android.medtronic.USB_REGISTER";
        public static final String ACTION_UPDATE_PUMP = "info.nightscout.android.medtronic.UPDATE_PUMP";

        public static final String EXTENDED_DATA = "info.nightscout.android.medtronic.service.DATA";
    }
}