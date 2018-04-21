/*
 *******************************************************************************
 * Adapted from:
 *
 *   Android U2F USB Bridge
 *   (c) 2016 Ledger
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 ********************************************************************************/

package to.crp.android.u2f;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Vector;

public final class U2FContext {

    private static final String TAG = "okd-context";

    private static final String TAG_JSON_APPID = "appId";
    private static final String TAG_JSON_REGISTERED_KEYS = "registeredKeys";
    private static final String TAG_JSON_REGISTER_REQUESTS = "registerRequests";
    private static final String TAG_JSON_CHALLENGE = "challenge";
    private static final String TAG_JSON_TYP = "typ";
    private static final String TAG_JSON_ORIGIN = "origin";
    private static final String TAG_JSON_CID_PUBKEY = "cid_pubkey";
    private static final String SIGN_REQUEST_TYPE = "u2f_sign_request";
    private static final String SIGN_RESPONSE_TYP = "navigator.id.getAssertion";
    private static final String REGISTER_REQUEST_TYPE = "u2f_register_request";
    private static final String REGISTER_RESPONSE_TYP = "navigator.id.finishEnrollment";
    private static final String CID_UNAVAILABLE = "unavailable";
    private static final String TAG_JSON_TYPE = "type";
    private static final String TAG_JSON_KEYHANDLE = "keyHandle";
    private static final String TAG_JSON_VERSION = "version";
    private static final String TAG_JSON_REQUESTID = "requestId";
    private static final String TAG_JSON_RESPONSEDATA = "responseData";
    private static final String TAG_JSON_CLIENTDATA = "clientData";
    private static final String TAG_JSON_SIGNATUREDATA = "signatureData";
    private static final String TAG_JSON_REGISTRATIONDATA = "registrationData";
    private static final String SIGN_RESPONSE_TYPE = "u2f_sign_response";
    private static final String REGISTER_RESPONSE_TYPE = "u2f_register_response";
    private static final String VERSION_U2F_V2 = "U2F_V2";

    private final String appId;
    private final byte[] challenge;
    private final List<byte[]> keyHandles;
    private byte[] chosenKeyHandle;
    private final int requestId;
    private final boolean sign;

    /**
     * @param appId      The appId.
     * @param challenge  The challenge.
     * @param keyHandles Key handles (sign exclusive).
     * @param requestId  The request ID.
     * @param sign       Sign context (true), register context (false)
     */
    private U2FContext(String appId, byte[] challenge, Vector<byte[]> keyHandles, int requestId,
                       boolean sign) {
        this.appId = appId;
        this.challenge = challenge;
        this.keyHandles = keyHandles;
        this.requestId = requestId;
        this.sign = sign;
    }

    public String getAppId() {
        return appId;
    }

    private byte[] getChallenge() {
        return challenge;
    }

    public List<byte[]> getKeyHandles() {
        return keyHandles;
    }

    public void setChosenKeyHandle(final byte[] chosenKeyHandle) {
        this.chosenKeyHandle = chosenKeyHandle;
    }

    private byte[] getChosenKeyHandle() {
        return chosenKeyHandle;
    }

    private int getRequestId() {
        return requestId;
    }

    public boolean isSign() {
        return sign;
    }

    /**
     * Get the intent response for the provided U2F context.
     *
     * @param u2fContext The U2F context.
     * @param data       Data required for generating the intent response.
     * @return Intent response String.
     * @throws IOException Thrown on error creating the intent response.
     */
    public static String createU2FResponse(final U2FContext u2fContext, final byte[] data) throws
            IOException {
        if (u2fContext.isSign()) {
            return createU2FResponseSign(u2fContext, data);
        } else {
            return createU2FResponseRegister(u2fContext, data);
        }
    }

    /**
     * Generate an intent response to a sign request.
     *
     * @param u2fContext The U2F context.
     * @param signature  The signature byte[].
     * @return Intent response String.
     * @throws IOException Thrown on error creating the intent response.
     */
    private static String createU2FResponseSign(final U2FContext u2fContext, final byte[] signature)
            throws IOException {
        try {
            final JSONObject response = new JSONObject();

            response.put(TAG_JSON_TYPE, SIGN_RESPONSE_TYPE);
            response.put(TAG_JSON_REQUESTID, u2fContext.getRequestId());

            final JSONObject responseData = new JSONObject();
            responseData.put(TAG_JSON_KEYHANDLE, Base64.encodeToString(u2fContext
                    .getChosenKeyHandle(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            responseData.put(TAG_JSON_SIGNATUREDATA, Base64.encodeToString(signature, 0,
                    signature.length - 2, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            final String clientData = createClientData(u2fContext);
            responseData.put(TAG_JSON_CLIENTDATA, Base64.encodeToString(clientData.getBytes
                    ("UTF-8"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));

            response.put(TAG_JSON_RESPONSEDATA, responseData);

            return response.toString();
        } catch (JSONException | IOException e) {
            throw new IOException("Error encoding request.", e);
        }
    }

    /**
     * Generate an intent response based on the register request.
     *
     * @param u2fContext       The U2F context.
     * @param registerResponse The last response from the register request.
     * @return Intent response String.
     * @throws IOException Thrown on error creating the intent response.
     */
    private static String createU2FResponseRegister(final U2FContext u2fContext, final byte[]
            registerResponse) throws IOException {
        try {
            final JSONObject response = new JSONObject();
            response.put(TAG_JSON_TYPE, REGISTER_RESPONSE_TYPE);
            response.put(TAG_JSON_REQUESTID, u2fContext.getRequestId());
            final JSONObject responseData = new JSONObject();
            responseData.put(TAG_JSON_REGISTRATIONDATA, Base64.encodeToString(registerResponse,
                    0, registerResponse.length - 2, Base64.URL_SAFE | Base64.NO_WRAP |
                            Base64.NO_PADDING));
            responseData.put(TAG_JSON_VERSION, VERSION_U2F_V2);
            final String clientData = createClientData(u2fContext);
            responseData.put(TAG_JSON_CLIENTDATA, Base64.encodeToString(clientData.getBytes
                    ("UTF-8"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            response.put(TAG_JSON_RESPONSEDATA, responseData);
            return response.toString();
        } catch (JSONException | IOException e) {
            throw new IOException("Error encoding request.", e);
        }
    }

    /**
     * Get a U2F context from the provided JSON-as-a-String representing the received U2F intent
     * request.
     * <p>
     * The returned context will be either a sign or register context.
     *
     * @param data JSON string representing the received U2F intent request.
     * @throws IOException Thrown on invalid request type or data parsing error.
     */
    public static U2FContext parseU2FContext(final String data) throws IOException {
        try {
            final JSONObject json = new JSONObject(data);
            final String requestType = json.getString(TAG_JSON_TYPE);

            switch (requestType) {
                case SIGN_REQUEST_TYPE:
                    return parseU2FContextSign(json);
                case REGISTER_REQUEST_TYPE:
                    return parseU2FContextRegister(json);
                default:
                    throw new IOException("Unhandled request type: " + requestType);
            }
        } catch (JSONException e) {
            throw new IOException("Error getting request type.", e);
        }
    }

    /**
     * Get a U2F sign context from JSON.
     *
     * @param json JSON representation of the received U2F intent request.
     * @return A U2F context.
     * @throws IOException Thrown on error decoding the JSON object.
     */
    private static U2FContext parseU2FContextSign(final JSONObject json) throws IOException {
        Log.d(TAG, "Parsing sign context.");
        try {
            final String appId = json.getString(TAG_JSON_APPID);
            final byte[] challenge = Base64.decode(json.getString(TAG_JSON_CHALLENGE),
                    Base64.URL_SAFE);
            final int requestId = json.getInt(TAG_JSON_REQUESTID);
            final JSONArray array = json.getJSONArray(TAG_JSON_REGISTERED_KEYS);
            final Vector<byte[]> keyHandles = new Vector<>();
            for (int i = 0; i < array.length(); i++) {
                final JSONObject keyHandleItem = array.getJSONObject(i);
                final String jsonVer = keyHandleItem.getString(TAG_JSON_VERSION);
                if (!jsonVer.equals(VERSION_U2F_V2)) {
                    throw new IOException("Invalid JSON version.");
                }
                byte[] keyHandle = Base64.decode(keyHandleItem.getString(TAG_JSON_KEYHANDLE),
                        Base64.URL_SAFE);
                keyHandles.add(keyHandle);
            }
            return new U2FContext(appId, challenge, keyHandles, requestId, true);
        } catch (JSONException e) {
            throw new IOException("Error decoding request", e);
        }
    }

    /**
     * Get a U2F register context from JSON.
     * <p>
     * Note: Does not support multiple register requests.
     *
     * @param json JSON representation of the received U2F intent request.
     * @return A U2F context.
     * @throws IOException Thrown on error decoding the JSON object.
     */
    private static U2FContext parseU2FContextRegister(final JSONObject json) throws IOException {
        Log.d(TAG, "Parsing register context.");
        try {
            byte[] challenge = null;
            final String appId = json.getString(TAG_JSON_APPID);
            final int requestId = json.getInt(TAG_JSON_REQUESTID);
            final JSONArray array = json.getJSONArray(TAG_JSON_REGISTER_REQUESTS);
            Log.d(TAG, "Have " + array.length() + " register requests.");
            for (int i = 0; i < array.length(); i++) {
                // TODO : only handle USB transport if several are present
                final JSONObject registerItem = array.getJSONObject(i);
                if (!registerItem.getString(TAG_JSON_VERSION).equals(VERSION_U2F_V2)) {
                    throw new IOException("Invalid register version");
                }
                challenge = Base64.decode(registerItem.getString(TAG_JSON_CHALLENGE),
                        Base64.URL_SAFE);
            }
            return new U2FContext(appId, challenge, null, requestId, false);
        } catch (JSONException e) {
            throw new IOException("Error decoding request", e);
        }
    }

    /**
     * Creates the client data for a sign or register request.
     *
     * @param u2fContext The U2F context.
     * @return Client data as a String.
     * @throws IOException Thrown on error creating the client data.
     */
    public static String createClientData(final U2FContext u2fContext) throws IOException {
        try {
            final JSONObject clientData = new JSONObject();
            clientData.put(TAG_JSON_TYP, (u2fContext.isSign() ? SIGN_RESPONSE_TYP :
                    REGISTER_RESPONSE_TYP));
            clientData.put(TAG_JSON_CHALLENGE, Base64.encodeToString(u2fContext.getChallenge(),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            clientData.put(TAG_JSON_ORIGIN, u2fContext.getAppId());
            clientData.put(TAG_JSON_CID_PUBKEY, CID_UNAVAILABLE);
            return clientData.toString();
        } catch (JSONException je) {
            throw new IOException("Error encoding client data.", je);
        }
    }
}
