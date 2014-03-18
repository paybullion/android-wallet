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

package com.preminer.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.Base58;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.params.MainNetParams;
import com.preminer.Configuration;
import com.preminer.R;
import com.preminer.WalletApplication;
import com.preminer.util.bip38.Bip38;
import com.preminer.util.bip38.lambdaworks.SCryptProgress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author EcuaMobi
 */
public final class ImportPrivKeyFragment extends SherlockFragment implements View.OnClickListener {
    private com.actionbarsherlock.view.MenuItem scanAction;
    private AbstractBindServiceActivity activity;
    private WalletApplication application;
    private Configuration config;
    private static final int REQUEST_CODE_SCAN = 0;

    private EditText txtPrivateKey, txtPassword;
    private TextView lblPassword, lblAddressTitle, lblAddress;
    private Button btnCancel, btnImport;

    private ECKey eckey;

    private static final Logger log = LoggerFactory.getLogger(ImportPrivKeyFragment.class);

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);
        this.activity = (AbstractBindServiceActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.import_privkey_fragment, container);

        txtPrivateKey = (EditText) view.findViewById(R.id.import_privkey_private_key);
        txtPrivateKey.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(final Editable s) {
                validatePrivateKey(false);
            }

            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            }
        });

        btnCancel = (Button) view.findViewById(R.id.import_privkey_cancel);
        btnCancel.setOnClickListener(this);
        btnImport = (Button) view.findViewById(R.id.import_privkey_import);
        btnImport.setOnClickListener(this);

        // Initially, import and sweep must be disabled, until a valid key is entered
        btnImport.setEnabled(false);

        txtPassword = (EditText) view.findViewById(R.id.import_privkey_bip38_password);
        lblPassword = (TextView) view.findViewById(R.id.import_privkey_bip38_label);
        lblAddressTitle = (TextView) view.findViewById(R.id.import_privkey_address_label);
        lblAddress = (TextView) view.findViewById(R.id.import_privkey_address);

        return view;
    }

    private void validatePrivateKey(boolean showErrors) {
        boolean isValid = false;
        Address address = null;
        String balance = null;
        eckey = null;

        try {
            MainNetParams params = MainNetParams.get();

            String privateKey = txtPrivateKey.getText().toString().trim();
            if (!privateKey.isEmpty()) {
                // Check if this is a BIP38-encrypted key
                if (Bip38.isBip38PrivateKey(privateKey)) {
                    byte[] key = Bip38.decrypt(privateKey, "jorge", new SCryptProgress(1, 1, 1), MainNetParams.get());
                    log.info("Private 16: " + Utils.bytesToHexString(key));
                    log.info("Private 64: " + Base64.encodeToString(key, 0));
                }

                if (params.isPrivateKeyValid(privateKey)) {
                    byte[] bytePrivateKey = Base58.decodeChecked(privateKey);
                    // Remove prefix (first byte) and checksum (last 4 bytes)
                    byte[] bytePrivateKeyRemoved = new byte[bytePrivateKey.length - 1];
                    for (int i = 0; i < bytePrivateKeyRemoved.length; ++i) {
                        bytePrivateKeyRemoved[i] = bytePrivateKey[i + 1];
                    }

                    // Create the ECKey only with the private key
                    eckey = new ECKey(bytePrivateKeyRemoved, null);
                    address = eckey.toAddress(params);

                    isValid = (null != address);
                }
            }
        } catch (final Exception x) {
            // could not decode address at all
            x.printStackTrace();
            log.error(getString(R.string.send_coins_fragment_receiving_address_error));
        }

        if (!isValid && showErrors) {
            Toast.makeText(activity, R.string.import_privkey_fragment_private_key_error, Toast.LENGTH_LONG).show();
            txtPrivateKey.setText("");
        }

        btnImport.setEnabled(isValid);
        lblAddress.setVisibility(isValid ? View.VISIBLE : View.GONE);
        lblAddressTitle.setVisibility(isValid ? View.VISIBLE : View.GONE);
        if (null != address) {
            lblAddress.setText(address.toString());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {

        super.onPause();
    }

    @Override
    public void onDetach() {
        super.onDetach();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        saveInstanceState(outState);
    }

    private void saveInstanceState(final Bundle outState) {
    }

    private void restoreInstanceState(final Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
            txtPrivateKey.setText(input);
            validatePrivateKey(true);
        }
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        inflater.inflate(R.menu.import_privkey_fragment_options, menu);

        scanAction = menu.findItem(R.id.import_privkey_options_scan);

        final PackageManager pm = activity.getPackageManager();
        scanAction.setVisible(pm.hasSystemFeature(PackageManager.FEATURE_CAMERA) || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.import_privkey_options_scan:
                handleScan();
                return true;

            case R.id.import_privkey_options_help:
                HelpDialogFragment.page(getFragmentManager(), R.string.help_import_privkey);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void handleScan() {
        startActivityForResult(new Intent(activity, ScanActivity.class), REQUEST_CODE_SCAN);
    }

    @Override
    public void onClick(View view) {
        final int id = view.getId();
        switch (id) {
            case R.id.import_privkey_cancel:
                activity.finish();
                return;

            case R.id.import_privkey_import:
                // Confirm
                final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.import_privkey_title);
                dialog.setMessage(R.string.import_privkey_confirm);
                dialog.setPositiveButton(R.string.import_privkey_button_import, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int id) {
                        application.getWallet().addKey(eckey);
                        application.resetBlockchain();
                        activity.finish();
                    }
                });
                dialog.setNegativeButton(R.string.button_cancel, null);
                dialog.show();

                return;
        }
    }
}
