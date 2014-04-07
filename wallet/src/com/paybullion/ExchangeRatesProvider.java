/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.paybullion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.format.DateUtils;

import com.google.bitcoin.core.Utils;
import com.paybullion.util.GenericUtils;
import com.paybullion.util.Io;

import org.json.JSONObject;
import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapPrimitive;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider {
    public static class ExchangeRate {
        public ExchangeRate(@Nonnull final String currencyCode, @Nonnull final BigInteger rate, final String source) {
            this.currencyCode = currencyCode;
            this.rate = rate;
            this.source = source;
        }

        public final String currencyCode;
        public BigInteger rate;
        public final String source;

        @Override
        public String toString() {
            return getClass().getSimpleName() + '[' + currencyCode + ':' + GenericUtils.formatValue(rate, Constants.BTC_MAX_PRECISION, 0) + ']';
        }
    }

    public static final String KEY_CURRENCY_CODE = "currency_code";
    private static final String KEY_RATE = "rate";
    private static final String KEY_SOURCE = "source";

    private Configuration config;

    @CheckForNull
    private Map<String, ExchangeRate> exchangeRates = null;
    private long lastUpdated = 0;

    private static final URL BITCOINAVERAGE_URL;
    private static final String[] BITCOINAVERAGE_FIELDS = new String[]{"24h_avg", "last"};
    private static final URL BITCOINCHARTS_URL;
    private static final String[] BITCOINCHARTS_FIELDS = new String[]{"24h", "7d", "30d"};
    private static final URL BLOCKCHAININFO_URL;
    private static final String[] BLOCKCHAININFO_FIELDS = new String[]{"15m"};
    // PBC
    private static final String PMC_CURRENCY = "PBC";
    private static final String GOLD_USER = "paybullion@grr.la";
    private static final String GOLD_PASSWORD = "f7yhdye8fijckl";

    static {
        try {
            BITCOINAVERAGE_URL = new URL("https://api.bitcoinaverage.com/ticker/global/all");
            BITCOINCHARTS_URL = new URL("http://api.bitcoincharts.com/v1/weighted_prices.json");
            BLOCKCHAININFO_URL = new URL("https://blockchain.info/ticker");
        } catch (final MalformedURLException x) {
            throw new RuntimeException(x); // cannot happen
        }
    }

    // PBC
    // Gold doesn't change its price as often as BTC
    // Query every 6h instead
    private static final long UPDATE_FREQ_MS = 6 * 60 * DateUtils.MINUTE_IN_MILLIS;

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

    @Override
    public boolean onCreate() {
        this.config = new Configuration(PreferenceManager.getDefaultSharedPreferences(getContext()));

        final ExchangeRate cachedExchangeRate = config.getCachedExchangeRate();
        if (cachedExchangeRate != null) {
            exchangeRates = new TreeMap<String, ExchangeRate>();
            exchangeRates.put(cachedExchangeRate.currencyCode, cachedExchangeRate);
        }

        return true;
    }

    public static Uri contentUri(@Nonnull final String packageName) {
        return Uri.parse("content://" + packageName + '.' + "exchange_rates");
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder) {
        final long now = System.currentTimeMillis();

        if (lastUpdated == 0 || now - lastUpdated > UPDATE_FREQ_MS) {
            try {
                // PBC
                // First, we need the USD<->PBC(gold) parity
                double doubleGoldRate = getGoldRate();

                log.info("Gold Rate: " + doubleGoldRate);

                if (0 == doubleGoldRate) {
                    return null;
                }

                BigInteger goldRate = GenericUtils.toNanoCoins(String.valueOf(doubleGoldRate), 0);

                Map<String, ExchangeRate> newExchangeRates = null;

                final String userAgent = "PayBullion Android Wallet";

                if (newExchangeRates == null)
                    newExchangeRates = requestExchangeRates(BITCOINAVERAGE_URL, userAgent, "", BITCOINAVERAGE_FIELDS);
                if (newExchangeRates == null)
                    newExchangeRates = requestExchangeRates(BITCOINCHARTS_URL, userAgent, "", BITCOINCHARTS_FIELDS);
                if (newExchangeRates == null)
                    newExchangeRates = requestExchangeRates(BLOCKCHAININFO_URL, userAgent, "", BLOCKCHAININFO_FIELDS);

                if (newExchangeRates != null) {
                    // PBC
                    // Get BTC<->USD value
                    ExchangeRate usdRate = newExchangeRates.get("USD");

                    final BigInteger nano = new BigInteger("1000000000");

                    BigInteger adjustRate = nano.multiply(goldRate).divide(usdRate.rate);

                    // Adjust all prices
                    for (String key : newExchangeRates.keySet()) {
                        ExchangeRate rate = newExchangeRates.get(key);
                        rate.rate = rate.rate.multiply(adjustRate).divide(nano);
                        newExchangeRates.put(key, rate);
                    }

                    exchangeRates = newExchangeRates;
                    lastUpdated = now;

                    final ExchangeRate exchangeRateToCache = bestExchangeRate(config.getExchangeCurrencyCode());
                    if (exchangeRateToCache != null)
                        config.setCachedExchangeRate(exchangeRateToCache);
                }
            } catch (Exception e) {
                return null;
            }
        }

        if (exchangeRates == null)
            return null;

        final MatrixCursor cursor = new MatrixCursor(new String[]{BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE, KEY_SOURCE});

        if (selection == null) {
            for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet()) {
                final ExchangeRate rate = entry.getValue();
                cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
            }
        } else if (selection.equals(KEY_CURRENCY_CODE)) {
            final ExchangeRate rate = bestExchangeRate(selectionArgs[0]);

            try {
                cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
            } catch (Exception e) {
            }
        }

        return cursor;
    }

    private ExchangeRate bestExchangeRate(final String currencyCode) {
        ExchangeRate rate = currencyCode != null ? exchangeRates.get(currencyCode) : null;
        if (rate != null)
            return rate;

        final String defaultCode = defaultCurrencyCode();
        rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

        if (rate != null)
            return rate;

        return exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);
    }

    private String defaultCurrencyCode() {
        try {
            return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
        } catch (final IllegalArgumentException x) {
            return null;
        }
    }

    public static ExchangeRate getExchangeRate(@Nonnull final Cursor cursor) {
        final String currencyCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
        final BigInteger rate = BigInteger.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE)));
        final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

        return new ExchangeRate(currencyCode, rate, source);
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(final Uri uri) {
        throw new UnsupportedOperationException();
    }

    // PBC
    private double getGoldRate() {
        SoapObject request = new SoapObject("http://freewebservicesx.com/", "GetCurrentGoldPrice");

        request.addProperty("UserName", GOLD_USER);
        request.addProperty("Password", GOLD_PASSWORD);

        SoapSerializationEnvelope envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);
        envelope.dotNet = true;
        envelope.setOutputSoapObject(request);
        HttpTransportSE httpTransport = new HttpTransportSE("http://www.freewebservicesx.com/GetGoldPrice.asmx");

        try {
            httpTransport.call("http://freewebservicesx.com/GetCurrentGoldPrice", envelope);

            SoapObject response = (SoapObject) envelope.getResponse();
            String goldRateString = ((SoapPrimitive) response.getProperty(0)).getValue().toString();

            NumberFormat format = NumberFormat.getInstance(Locale.US);
            Number goldRate = format.parse(goldRateString);
            return goldRate.doubleValue();
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        return 0;
    }

    private static Map<String, ExchangeRate> requestExchangeRates(final URL url, final String userAgent, final String source, final String... fields) {
        final long start = System.currentTimeMillis();

        HttpURLConnection connection = null;
        Reader reader = null;

        try {
            connection = (HttpURLConnection) url.openConnection();

            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
            connection.addRequestProperty("User-Agent", userAgent);
            connection.addRequestProperty("Accept-Encoding", "gzip");
            connection.connect();

            final int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                final String contentEncoding = connection.getContentEncoding();

                InputStream is = new BufferedInputStream(connection.getInputStream(), 1024);
                if ("gzip".equalsIgnoreCase(contentEncoding))
                    is = new GZIPInputStream(is);

                reader = new InputStreamReader(is, Constants.UTF_8);
                final StringBuilder content = new StringBuilder();
                final long length = Io.copy(reader, content);

                final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

                final JSONObject head = new JSONObject(content.toString());
                for (final Iterator<String> i = head.keys(); i.hasNext(); ) {
                    final String currencyCode = i.next();
                    if (!"timestamp".equals(currencyCode)) {
                        final JSONObject o = head.getJSONObject(currencyCode);

                        for (final String field : fields) {
                            final String rateStr = o.optString(field, null);

                            if (rateStr != null) {
                                try {
                                    final BigInteger rate = GenericUtils.toNanoCoins(rateStr, 0);

                                    if (rate.signum() > 0) {
                                        rates.put(currencyCode, new ExchangeRate(currencyCode, rate, source));
                                        break;
                                    }
                                } catch (final ArithmeticException x) {
                                    log.warn("problem fetching {} exchange rate from {} ({}): {}", currencyCode, url, contentEncoding, x.getMessage());
                                }
                            }
                        }
                    }
                }

                log.info("fetched exchange rates from {} ({}), {} chars, took {} ms", url, contentEncoding, length, System.currentTimeMillis()
                        - start);

                return rates;
            } else {
                log.warn("http status {} when fetching {}", responseCode, url);
            }
        } catch (final Exception x) {
            log.warn("problem fetching exchange rates from " + url, x);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException x) {
                    // swallow
                }
            }

            if (connection != null)
                connection.disconnect();
        }

        return null;
    }
}
