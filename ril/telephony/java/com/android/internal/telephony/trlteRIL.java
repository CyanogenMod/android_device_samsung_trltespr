/*
 * Copyright (c) 2014, The CyanogenMod Project. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.telephony.SmsMessage;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.telephony.Rlog;

import android.telephony.SignalStrength;

import android.telephony.PhoneNumberUtils;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;

/**
 * RIL customization for Galaxy Note 4 (CDMA) LTE devices
 *
 * {@hide}
 */
public class trlteRIL extends RIL {

    private Object mSMSLock = new Object();
    private boolean mIsSendingSMS = false;
    protected boolean isGSM = false;
    public static final long SEND_SMS_TIMEOUT_IN_MS = 30000;

    private static final int RIL_REQUEST_DIAL_EMERGENCY = 10016;
    private static final int RIL_UNSOL_DEVICE_READY_NOTI = 11008;
    private static final int RIL_UNSOL_AM = 11010;
    private static final int RIL_UNSOL_WB_AMR_STATE = 11017;
    private static final int RIL_UNSOL_SRVCC_HANDOVER = 11029;
    private static final int RIL_REQUEST_ACTIVATE_DATA_CALL = 11037;
    private Message mPendingGetSimStatus;

    public trlteRIL(Context context, int preferredNetworkType,
            int cdmaSubscription, Integer instanceId) {
        this(context, preferredNetworkType, cdmaSubscription);
	}

    public trlteRIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
        mQANElements = 6;
    }

    @Override
    protected void notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        final int response = RIL_UNSOL_CDMA_INFO_REC;
 
        if (infoRec.record instanceof CdmaSignalInfoRec) {
            CdmaSignalInfoRec sir = (CdmaSignalInfoRec) infoRec.record;
            if (sir != null
                    && sir.isPresent
                    && sir.signalType == SignalToneUtil.IS95_CONST_IR_SIGNAL_IS54B
                    && sir.alertPitch == SignalToneUtil.IS95_CONST_IR_ALERT_MED
                    && sir.signal == SignalToneUtil.IS95_CONST_IR_SIG_IS54B_L) {
 
                Rlog.d(RILJ_LOG_TAG, "Dropping \"" + responseToString(response) + " "
                        + retToString(response, sir)
                        + "\" to prevent \"ring of death\" bug.");
                return;
            }
        }
 
        super.notifyRegistrantsCdmaInfoRec(infoRec);
    }

    private void
    handleNitzTimeReceived(Parcel p) {
        String nitz = (String)responseString(p);
        //if (RILJ_LOGD) unsljLogRet(RIL_UNSOL_NITZ_TIME_RECEIVED, nitz);
 
        // has bonus long containing milliseconds since boot that the NITZ
        // time was received
        long nitzReceiveTime = p.readLong();
 
        Object[] result = new Object[2];
 
        String fixedNitz = nitz;
        String[] nitzParts = nitz.split(",");
        if (nitzParts.length == 4) {
            // 0=date, 1=time+zone, 2=dst, 3=garbage that confuses GsmServiceStateTracker (so remove it)
            fixedNitz = nitzParts[0]+","+nitzParts[1]+","+nitzParts[2]+",";
        }
 
        result[0] = fixedNitz;
        result[1] = Long.valueOf(nitzReceiveTime);
 
        boolean ignoreNitz = SystemProperties.getBoolean(
                        TelephonyProperties.PROPERTY_IGNORE_NITZ, false);
 
        if (ignoreNitz) {
            if (RILJ_LOGD) riljLog("ignoring UNSOL_NITZ_TIME_RECEIVED");
        } else {
            if (mNITZTimeRegistrant != null) {
                mNITZTimeRegistrant
                .notifyRegistrant(new AsyncResult (null, result, null));
            } else {
                // in case NITZ time registrant isnt registered yet
                mLastNITZTimeInfo = result;
            }
        }
    }
 
    @Override
    protected Object
    responseSMS(Parcel p) {
        // Notify that sendSMS() can send the next SMS
        synchronized (mSMSLock) {
            mIsSendingSMS = false;
            mSMSLock.notify();
        }
 
        return super.responseSMS(p);
    }
 
    @Override
    public void
    dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        if (PhoneNumberUtils.isEmergencyNumber(address)) {
            dialEmergencyCall(address, clirMode, result);
            return;
        }
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL, result);
 
        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeString("");
 
        if (uusInfo == null) {
            rr.mParcel.writeInt(0); // UUS information is absent
        } else {
            rr.mParcel.writeInt(1); // UUS information is present
            rr.mParcel.writeInt(uusInfo.getType());
            rr.mParcel.writeInt(uusInfo.getDcs());
            rr.mParcel.writeByteArray(uusInfo.getUserData());
        }
 
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
 
        send(rr);
    }
 
    //this method is used in the search network functionality.
    // in mobile network setting-> network operators
    @Override
    protected Object
    responseOperatorInfos(Parcel p) {
        String strings[] = (String [])responseStrings(p);
        ArrayList<OperatorInfo> ret;
 
        if (strings.length % mQANElements != 0) {
            throw new RuntimeException(
                                       "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS: invalid response. Got "
                                       + strings.length + " strings, expected multiple of " + mQANElements);
        }
 
        ret = new ArrayList<OperatorInfo>(strings.length / mQANElements);
        Operators init = null;
        if (strings.length != 0) {
            init = new Operators();
        }
        for (int i = 0 ; i < strings.length ; i += mQANElements) {
            String temp = init.unOptimizedOperatorReplace(strings[i+0]);
            ret.add (
                     new OperatorInfo(
                                      temp, //operatorAlphaLong
                                      temp,//operatorAlphaShort
                                      strings[i+2],//operatorNumeric
                                      strings[i+3]));//state
        }
 
        return ret;
    }
 
    @Override
    public void getImsRegistrationState(Message result) {
        if(mRilVersion >= 8)
            super.getImsRegistrationState(result);
        else {
            if (result != null) {
                CommandException ex = new CommandException(
                    CommandException.Error.REQUEST_NOT_SUPPORTED);
                AsyncResult.forMessage(result, null, ex);
                result.sendToTarget();
            }
        }
    }
 
    public void
    dialEmergencyCall(String address, int clirMode, Message result) {
        RILRequest rr;
        Rlog.v(RILJ_LOG_TAG, "Emergency dial: " + address);
 
        rr = RILRequest.obtain(RIL_REQUEST_DIAL_EMERGENCY, result);
        rr.mParcel.writeString(address + "/");
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);  // UUS information is absent
 
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
 
        send(rr);
    }
 
    // Hack for Lollipop
    // The system now queries for SIM status before radio on, resulting
    // in getting an APPSTATE_DETECTED state. The RIL does not send an
    // RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED message after the SIM is
    // initialized, so delay the message until the radio is on.
    @Override
    public void
    getIccCardStatus(Message result) {
        if (mState != RadioState.RADIO_ON) {
            mPendingGetSimStatus = result;
        } else {
            super.getIccCardStatus(result);
        }
    }
 
    @Override
    protected void switchToRadioState(RadioState newState) {
        super.switchToRadioState(newState);
 
        if (newState == RadioState.RADIO_ON && mPendingGetSimStatus != null) {
            super.getIccCardStatus(mPendingGetSimStatus);
            mPendingGetSimStatus = null;
        }
    }

    @Override
    public void
    acceptCall (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_ANSWER, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
		rr.mParcel.writeInt(1);
		rr.mParcel.writeInt(0);
        send(rr);
    }

    @Override
    public void
    sendCdmaSms(byte[] pdu, Message result) {
        smsLock();
        super.sendCdmaSms(pdu, result);
    }

    @Override
    public void
        sendSMS (String smscPDU, String pdu, Message result) {
        smsLock();
        super.sendSMS(smscPDU, pdu, result);
    }

    private void smsLock(){
        // Do not send a new SMS until the response for the previous SMS has been received
        //   * for the error case where the response never comes back, time out after
        //     30 seconds and just try the next SEND_SMS
        synchronized (mSMSLock) {
            long timeoutTime  = SystemClock.elapsedRealtime() + SEND_SMS_TIMEOUT_IN_MS;
            long waitTimeLeft = SEND_SMS_TIMEOUT_IN_MS;
            while (mIsSendingSMS && (waitTimeLeft > 0)) {
                Rlog.d(RILJ_LOG_TAG, "sendSMS() waiting for response of previous SEND_SMS");
                try {
                    mSMSLock.wait(waitTimeLeft);
                } catch (InterruptedException ex) {
                    // ignore the interrupt and rewait for the remainder
                }
                waitTimeLeft = timeoutTime - SystemClock.elapsedRealtime();
            }
            if (waitTimeLeft <= 0) {
                Rlog.e(RILJ_LOG_TAG, "sendSms() timed out waiting for response of previous CDMA_SEND_SMS");
            }
            mIsSendingSMS = true;
        }

    }

    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplicationStatus appStatus;

        IccCardStatus cardStatus = new IccCardStatus();
        cardStatus.setCardState(p.readInt());
        cardStatus.setUniversalPinState(p.readInt());
        cardStatus.mGsmUmtsSubscriptionAppIndex = p.readInt();
        cardStatus.mCdmaSubscriptionAppIndex = p.readInt();
        cardStatus.mImsSubscriptionAppIndex = p.readInt();

        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        cardStatus.mApplications = new IccCardApplicationStatus[numApplications];

        for (int i = 0 ; i < numApplications ; i++) {
            appStatus = new IccCardApplicationStatus();
            appStatus.app_type       = appStatus.AppTypeFromRILInt(p.readInt());
            appStatus.app_state      = appStatus.AppStateFromRILInt(p.readInt());
            appStatus.perso_substate = appStatus.PersoSubstateFromRILInt(p.readInt());
            appStatus.aid            = p.readString();
            appStatus.app_label      = p.readString();
            appStatus.pin1_replaced  = p.readInt();
            appStatus.pin1           = appStatus.PinStateFromRILInt(p.readInt());
            appStatus.pin2           = appStatus.PinStateFromRILInt(p.readInt());
            p.readInt(); // pin1_num_retries
            p.readInt(); // puk1_num_retries
            p.readInt(); // pin2_num_retries
            p.readInt(); // puk2_num_retries
            p.readInt(); // perso_unblock_retries

            cardStatus.mApplications[i] = appStatus;
        }
        return cardStatus;
    }

    @Override
    protected Object
    responseCallList(Parcel p) {
        int num;
        int voiceSettings;
        ArrayList<DriverCall> response;
        DriverCall dc;

        num = p.readInt();
        response = new ArrayList<DriverCall>(num);

        if (RILJ_LOGV) {
            riljLog("responseCallList: num=" + num +
                    " mEmergencyCallbackModeRegistrant=" + mEmergencyCallbackModeRegistrant +
                    " mTestingEmergencyCall=" + mTestingEmergencyCall.get());
        }
        for (int i = 0 ; i < num ; i++) {
            dc = new DriverCall();

            dc.state = DriverCall.stateFromCLCC(p.readInt());
            dc.index = p.readInt() & 0xff;
            dc.TOA = p.readInt();
            dc.isMpty = (0 != p.readInt());
            dc.isMT = (0 != p.readInt());
            dc.als = p.readInt();
            voiceSettings = p.readInt();
            dc.isVoice = (0 != voiceSettings);
            boolean isVideo = (0 != p.readInt());   // Samsung CallDetails
            int call_type = p.readInt();            // Samsung CallDetails
            int call_domain = p.readInt();          // Samsung CallDetails
            String csv = p.readString();            // Samsung CallDetails
            dc.isVoicePrivacy = (0 != p.readInt());
            dc.number = p.readString();
            int np = p.readInt();
            dc.numberPresentation = DriverCall.presentationFromCLIP(np);
            dc.name = p.readString();
            dc.namePresentation = p.readInt();
            int uusInfoPresent = p.readInt();
            if (uusInfoPresent == 1) {
                dc.uusInfo = new UUSInfo();
                dc.uusInfo.setType(p.readInt());
                dc.uusInfo.setDcs(p.readInt());
                byte[] userData = p.createByteArray();
                dc.uusInfo.setUserData(userData);
                riljLogv(String.format("Incoming UUS : type=%d, dcs=%d, length=%d",
                                dc.uusInfo.getType(), dc.uusInfo.getDcs(),
                                dc.uusInfo.getUserData().length));
                riljLogv("Incoming UUS : data (string)="
                        + new String(dc.uusInfo.getUserData()));
                riljLogv("Incoming UUS : data (hex): "
                        + IccUtils.bytesToHexString(dc.uusInfo.getUserData()));
            } else {
                riljLogv("Incoming UUS : NOT present!");
            }

            // Make sure there's a leading + on addresses with a TOA of 145
            dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);

            response.add(dc);

            if (dc.isVoicePrivacy) {
                mVoicePrivacyOnRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is enabled");
            } else {
                mVoicePrivacyOffRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is disabled");
            }
        }

        Collections.sort(response);

        if ((num == 0) && mTestingEmergencyCall.getAndSet(false)) {
            if (mEmergencyCallbackModeRegistrant != null) {
                riljLog("responseCallList: call ended, testing emergency call," +
                            " notify ECM Registrants");
                mEmergencyCallbackModeRegistrant.notifyRegistrant();
            }
        }

        return response;
    }

    @Override
    protected Object
    responseSignalStrength(Parcel p) {
        int gsmSignalStrength = p.readInt() & 0xff;
        int gsmBitErrorRate = p.readInt();
        int cdmaDbm = p.readInt();
        int cdmaEcio = p.readInt();
        int evdoDbm = p.readInt();
        int evdoEcio = p.readInt();
        int evdoSnr = p.readInt();
        int lteSignalStrength = p.readInt();
        int lteRsrp = p.readInt();
        int lteRsrq = p.readInt();
        int lteRssnr = p.readInt();
        int lteCqi = p.readInt();
        int tdScdmaRscp = p.readInt();
        // constructor sets default true, makeSignalStrengthFromRilParcel does not set it
        boolean isGsm = false;

        if ((lteSignalStrength & 0xff) == 255 || lteSignalStrength == 99) {
            lteSignalStrength = 99;
            lteRsrp = SignalStrength.INVALID;
            lteRsrq = SignalStrength.INVALID;
            lteRssnr = SignalStrength.INVALID;
            lteCqi = SignalStrength.INVALID;
        } else {
            lteSignalStrength &= 0xff;
        }

        if (RILJ_LOGD)
            riljLog("gsmSignalStrength:" + gsmSignalStrength + " gsmBitErrorRate:" + gsmBitErrorRate +
                    " cdmaDbm:" + cdmaDbm + " cdmaEcio:" + cdmaEcio + " evdoDbm:" + evdoDbm +
                    " evdoEcio: " + evdoEcio + " evdoSnr:" + evdoSnr +
                    " lteSignalStrength:" + lteSignalStrength + " lteRsrp:" + lteRsrp +
                    " lteRsrq:" + lteRsrq + " lteRssnr:" + lteRssnr + " lteCqi:" + lteCqi +
                    " tdScdmaRscp:" + tdScdmaRscp + " isGsm:" + (isGsm ? "true" : "false"));

        return new SignalStrength(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio, evdoDbm,
                evdoEcio, evdoSnr, lteSignalStrength, lteRsrp, lteRsrq, lteRssnr, lteCqi,
                tdScdmaRscp, isGsm);
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                ret = responseVoid(p);
                break;
            case RIL_UNSOL_DEVICE_READY_NOTI:
                ret = responseVoid(p);
                break;
            case RIL_UNSOL_AM:
                ret = responseString(p);
                break;
            case RIL_UNSOL_WB_AMR_STATE:
                ret = responseInts(p);
                break;
            case RIL_UNSOL_SRVCC_HANDOVER:
                ret = responseVoid(p);
                break;
            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);

                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p);
                return;
        }
    }

}
