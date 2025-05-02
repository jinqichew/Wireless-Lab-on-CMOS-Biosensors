#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <string>   // Use C++ standard string library
#include <cstring>  // For strtok, strcpy
#include <cmath>

// --- Component Addresses ---
#define LMP91000_ADDR 0x48 // AFE for Amperometry/CV
#define MCP4725_ADDR  0x60 // DAC (A0 to GND)

// LMP91000 Register Addresses
#define LMP91000_STATUS_REG   0x00
#define LMP91000_LOCK_REG     0x01
#define LMP91000_TIACN_REG    0x10
#define LMP91000_REFCN_REG    0x11
#define LMP91000_MODECN_REG   0x12

// MCP4725 Commands
#define MCP4725_CMD_WRITE_DAC          0x40
#define MCP4725_CMD_WRITE_DAC_EEPROM   0x60

// ESP32-C3 I2C Pins
#define I2C_SDA 6 // 1
#define I2C_SCL 5 // 0

// --- ANALOG PIN ---
#define LMP91000_VOUT_PIN 4
#define LMP91200_VOUT_PIN 1  
#define LMP91200_VOCM_PIN 0

// BLE Service and Characteristic UUIDs
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" // UART Service
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // RX Characteristic
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // TX Characteristic

// Function prototypes
void lmp91000_write_register(uint8_t reg_addr, uint8_t data);
uint8_t lmp91000_read_register(uint8_t reg_addr);
void mcp4725_set_voltage(uint16_t dac_value, bool write_to_eeprom = false);
float read_lmp91000_voltage();
void lmp91000_setup();
void mcp4725_setup();
float calculate_cell_voltage(float vref, uint8_t refcn_reg);

void cv1(float end_voltage, float voltage_step, unsigned long cv_delay_ms, int direction);
void cv2(float end_voltage, float voltage_step, unsigned long cv_delay_ms, int direction, int final_loop=0);
void alt_cv1(float end_voltage, float voltage_step, unsigned long cv_delay_ms, int direction);
void alt_cv2(float end_voltage, float voltage_step, unsigned long cv_delay_ms, int direction, int final_loop=0);
void cv_nonzero1(float start_voltage, float end_voltage, float voltage_step, unsigned long cv_delay_ms, int sign);
void cv_nonzero2(float start_voltage, float end_voltage, float voltage_step, unsigned long cv_delay_ms, int sign);
void setupBLE(String BLEName); 
void cv(float lower_limit, float upper_limit, float sweep_rate, int n_loop, unsigned long cv_delay_ms=100);
void send_cv_data_to_app(float vout, float cell_voltage, int end_flag=0);

void potentiometry(unsigned long duration_ms, unsigned long sample_interval_ms);
void send_pot_data_to_app(float elapsedTime, float voltage);
float read_lmp91200_pot_voltage();

// --- Global Variables ---
BLECharacteristic *pTxCharacteristic;
BLECharacteristic *pRxCharacteristic; // Declare the RX characteristic globally
bool deviceConnected = false;
uint16_t dac_value_global = 0;  // Stores the current DAC *value* (0-4095)
const float measured_VDD = 3.289;
const int adc_samples = 10;
const float calibration_slope = 0.93108;
const float calibration_offset = 0.022906;
float actual_end_voltage;
unsigned long previousTime;

// CV parameters (to be received from the app)
float lower_limit = -0.6;  // Default values. These can be changed via BLE
float upper_limit = 0.6;   // or used when triggering via Serial.
float sweep_rate = 0.15;
int n_loop = 2;
unsigned long cv_delay_ms = 100; //default
bool cv_running = false;     // Flag to indicate if CV is in progress

// Potentiometry
unsigned long pot_duration_ms = 10000; // Default 10 seconds
unsigned long pot_sampling_interval_ms = 100; // Default sample every 100ms
bool pot_running = false; 

// --- BLE Server Callbacks ---
class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        Serial.println("Device Connected");
    };

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        Serial.println("Device Disconnected");
        pServer->getAdvertising()->start(); // Restart advertising
    }
};

// --- BLE Characteristic Callbacks (for receiving data) ---
class MyCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
        std::string rxValue;
        uint8_t* rxData = pCharacteristic->getData(); // Get the raw data (uint8_t*)
        size_t length = pCharacteristic->getLength(); // Get the length of the data

        if (length > 0) {
            // Construct a std::string from the received data.
            rxValue = std::string((char*)rxData, length);

            Serial.print("Received BLE message: ");
            Serial.println(rxValue.c_str());

            // Tokenise the received string
            char *token;
            char *cstr = new char[rxValue.length() + 1];
            strcpy(cstr, rxValue.c_str());
            int command_indicator = -1; // Initialize to an invalid value

            token = strtok(cstr, ",");
            if (token != NULL) {
                command_indicator = atoi(token);
            }

            // Prevent starting a new test if one is already running
            if (cv_running || pot_running) {
                 Serial.println("Test already in progress. Ignoring BLE command.");
                 delete[] cstr;
                 return;
            }

            Serial.print("Parsed Command Indicator: ");
            Serial.println(command_indicator);

            if (command_indicator == 0) { // CV Test
                Serial.println("Parsing CV parameters from BLE...");
                token = strtok(NULL, ","); if (token != NULL) lower_limit = atof(token);
                token = strtok(NULL, ","); if (token != NULL) upper_limit = atof(token); 
                token = strtok(NULL, ","); if (token != NULL) sweep_rate = atof(token);
                token = strtok(NULL, ","); if (token != NULL) n_loop = atoi(token); 

                Serial.println("Starting CV test via BLE command.");
                Serial.print("Params: L="); Serial.print(lower_limit);
                Serial.print(" U="); Serial.print(upper_limit);
                Serial.print(" R="); Serial.print(sweep_rate);
                Serial.print(" N="); Serial.println(n_loop);
                cv_running = true;

            } else if (command_indicator == 1) { // Potentiometry Test
                Serial.println("Parsing Potentiometry parameters from BLE...");
                token = strtok(NULL, ","); if (token != NULL) pot_duration_ms = atol(token)*1000; 
                token = strtok(NULL, ","); if (token != NULL) pot_sampling_interval_ms = atol(token); else {  }

                Serial.println("Starting Potentiometry test via BLE command.");
                Serial.print("Params: Duration="); Serial.print(pot_duration_ms);
                Serial.print("ms, Interval="); Serial.print(pot_sampling_interval_ms); Serial.println("ms");
                pot_running = true; // <<< SET POT FLAG

            } else {
                 Serial.println("Received unknown command indicator via BLE.");
            }

            goto cleanup; // Skip error message if parsing was successful

        parse_error:
            Serial.println("Error parsing BLE parameters.");

        cleanup:
            delete[] cstr; // Free the dynamically allocated memory
        }
    }
};

void setup() {
    Serial.begin(115200);
    Wire.begin(I2C_SDA, I2C_SCL);
    delay(100);

    pinMode(LMP91200_VOUT_PIN, INPUT);
    pinMode(LMP91200_VOCM_PIN, INPUT);
    lmp91000_setup();
    mcp4725_setup();
    setupBLE("ESP32_sensor"); // Initialize BLE

    Serial.println("System Ready.");
    Serial.println("Enter 'cv' or 'pot' in Serial Monitor to start tests.");
}

void loop() {
    // --- Check for Serial Monitor Input ---
    if (Serial.available() > 0) {
        String input = Serial.readStringUntil('\n');
        input.trim();
        input.toLowerCase(); // Make comparison case-insensitive

        if (cv_running || pot_running) {
            Serial.println("Test already in progress. Ignoring serial command.");
        }
        else {
          if (input == "cv"){
            Serial.println("Starting CV test via Serial Monitor command.");
            Serial.print("Using current parameters: lower=");
            Serial.print(lower_limit);
            Serial.print(", upper=");
            Serial.print(upper_limit);
            Serial.print(", rate=");
            Serial.print(sweep_rate);
            Serial.print(", loops=");
            Serial.println(n_loop);
            cv_running = true; // Set flag to start CV
          } 
          else if (input == "pot") {
            Serial.println("Starting Potentiometry test via Serial Monitor command.");
            Serial.print("Using current/default params: Duration="); Serial.print(pot_duration_ms);
            Serial.print("ms, Interval="); Serial.print(pot_sampling_interval_ms); Serial.println("ms");
            pot_running = true;
          }
        }
    }

    // --- Check if CV needs to run ---
    if (cv_running) {
        Serial.println("Executing cv() function...");
        // Print CSV header (only once at the beginning of the test)
        Serial.println("VREF (V),LMP91000 VOUT (V),Cell Voltage (V),VOUT_minusIZ,Setting,REFCN (BIN), Step Time (ms)");
        cv(lower_limit, upper_limit, sweep_rate, n_loop, cv_delay_ms);
        cv_running = false; // Reset the flag after CV is complete
        Serial.println("CV test finished.");
        Serial.println("-----------------------------------------");
        Serial.println("Ready for next command (BLE or Serial).");

    }
    else if (pot_running) { // Execute Potentiometry
        Serial.println("Executing potentiometry() function...");
        Serial.println("ElapsedTime(ms),LMP91200_VOUT(V)"); // CSV Header
        potentiometry(pot_duration_ms, pot_sampling_interval_ms); // <<< Pass interval
        pot_running = false;
        Serial.println("Potentiometry test finished.");
        Serial.println("-----------------------------------------");
        Serial.println("Ready for next command (BLE or Serial).");
    }

    delay(50); // Keep a small delay in the main loop
}

// --- BLE Setup Function ---

void setupBLE(String BLEName) { // Keep using String for the BLE name
    const char* ble_name = BLEName.c_str();
    BLEDevice::init(ble_name);
    BLEServer* pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks()); // Set the server callbacks

    BLEService* pService = pServer->createService(SERVICE_UUID);

    // TX Characteristic (for sending data to the app)
    pTxCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID_TX,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    pTxCharacteristic->addDescriptor(new BLE2902());

    // RX Characteristic (for receiving data from app)
    pRxCharacteristic = pService->createCharacteristic( // *** Instantiate pRxCharacteristic
        CHARACTERISTIC_UUID_RX,
        BLECharacteristic::PROPERTY_WRITE
    );

    pRxCharacteristic->setCallbacks(new MyCallbacks()); // *** Set callbacks for RX


    pService->start();
    pServer->getAdvertising()->start();
    Serial.println("Waiting for a client connection...");
}

// --- Send Data to App via BLE ---

void send_cv_data_to_app(float vout, float cell_voltage, int end_flag) {
    float Iout = vout/1;
    if (!deviceConnected) return; // Don't try to send if not connected

    uint8_t data[8]; // Two floats = 8 bytes
    memcpy(data, &Iout, 4);
    memcpy(data + 4, &cell_voltage, 4);
    memcpy(data + 4, &end_flag, 4);
    pTxCharacteristic->setValue(data, sizeof(data));
    pTxCharacteristic->notify(); // Notify the client (app)
}

void send_pot_data_to_app(float elapsedTime, float voltage) {
    if (!deviceConnected) return;
    uint8_t data[8]; // Two floats = 8 bytes
    memcpy(data, &elapsedTime, 4); // Send elapsed time as the first float
    memcpy(data + 4, &voltage, 4);   // Send voltage as the second float
    pTxCharacteristic->setValue(data, sizeof(data));
    pTxCharacteristic->notify();
    delay(1); // Small delay
}

// --- LMP91000 Functions ---

void lmp91000_write_register(uint8_t reg_addr, uint8_t data) {
    Wire.beginTransmission(LMP91000_ADDR);
    Wire.write(reg_addr);
    Wire.write(data);
    Wire.endTransmission();
}

uint8_t lmp91000_read_register(uint8_t reg_addr) {
    uint8_t data;
    Wire.beginTransmission(LMP91000_ADDR);
    Wire.write(reg_addr);
    Wire.endTransmission(false);
    Wire.requestFrom(LMP91000_ADDR, 1);
    if (Wire.available()) {
        data = Wire.read();
    }
    return data;
}

float read_lmp91000_voltage() {
    long sum = 0;
    for (int i = 0; i < adc_samples; i++) {
        sum += analogRead(LMP91000_VOUT_PIN);
        delayMicroseconds(50); // Small delay
    }
    int analog_value = sum / adc_samples;
    float voltage = (float)analog_value * (measured_VDD / 4095.0);

    float calibrated_voltage = (calibration_slope * voltage) + calibration_offset;

    return calibrated_voltage;
}

void lmp91000_setup() {
    lmp91000_write_register(LMP91000_LOCK_REG, 0x00);
    lmp91000_write_register(LMP91000_TIACN_REG, 0b000011111); // 2.75k TIA gain
    lmp91000_write_register(LMP91000_REFCN_REG, 0b10101101); // External ref, 50% zero, pos bias, 24% bias
    lmp91000_write_register(LMP91000_MODECN_REG, 0b00000011); // 3-lead mode
    lmp91000_write_register(LMP91000_LOCK_REG, 0x01);

    Serial.print("TIACN Register: 0x");
    Serial.println(lmp91000_read_register(LMP91000_TIACN_REG), HEX);
    Serial.print("REFCN Register: 0x");
    Serial.println(lmp91000_read_register(LMP91000_REFCN_REG), HEX);
    Serial.print("MODECN Register: 0x");
    Serial.println(lmp91000_read_register(LMP91000_MODECN_REG), HEX);
}

// --- MCP4725 Functions ---

void mcp4725_set_voltage(uint16_t dac_value, bool write_to_eeprom) {
    dac_value = constrain(dac_value, 0, 4095);
    uint8_t command_byte = (write_to_eeprom) ? MCP4725_CMD_WRITE_DAC_EEPROM : MCP4725_CMD_WRITE_DAC;
    uint8_t high_byte = (dac_value >> 4) & 0xFF;
    uint8_t low_byte  = (dac_value << 4) & 0xF0;

    Wire.beginTransmission(MCP4725_ADDR);
    Wire.write(command_byte);
    Wire.write(high_byte);
    Wire.write(low_byte);
    Wire.endTransmission();
}

void mcp4725_setup(){
     mcp4725_set_voltage(0); //initialise to 0
}

// --- Calculate Cell Voltage Function ---

float calculate_cell_voltage(float vref, uint8_t refcn_reg) {
    uint8_t int_z_setting = (refcn_reg >> 5) & 0x03;
    uint8_t bias_sign = (refcn_reg >> 4) & 0x01;
    uint8_t bias_setting = refcn_reg & 0x0F;

    float internal_zero;
    if (int_z_setting == 0b00) {
        internal_zero = 0.2 * vref;
    } else if (int_z_setting == 0b01) {
        internal_zero = 0.5 * vref;
    } else if (int_z_setting == 0b10) {
        internal_zero = 0.67 * vref;
    } else {
        return NAN;
    }

    float bias_percentage;
    switch (bias_setting) {
        case 0b0000: bias_percentage = 0.00; break;
        case 0b0001: bias_percentage = 0.01; break;
        case 0b0010: bias_percentage = 0.02; break;
        case 0b0011: bias_percentage = 0.04; break;
        case 0b0100: bias_percentage = 0.06; break;
        case 0b0101: bias_percentage = 0.08; break;
        case 0b0110: bias_percentage = 0.10; break;
        case 0b0111: bias_percentage = 0.12; break;
        case 0b1000: bias_percentage = 0.14; break;
        case 0b1001: bias_percentage = 0.16; break;
        case 0b1010: bias_percentage = 0.18; break;
        case 0b1011: bias_percentage = 0.20; break;
        case 0b1100: bias_percentage = 0.22; break;
        case 0b1101: bias_percentage = 0.24; break;
        default:     return NAN;
    }

    float bias_voltage = bias_percentage * vref;

    if (bias_sign == 0) {
        bias_voltage = -bias_voltage;
    }

    float cell_voltage = bias_voltage;

    return cell_voltage;
}

float read_lmp91200_pot_voltage() {
    long sum = 0;
    for (int i = 0; i < adc_samples; i++) { // Use adc_samples global variable
        sum += analogRead(LMP91200_VOUT_PIN);
        delayMicroseconds(50); // Small delay between samples
    }
    int analog_value = sum / adc_samples;
    // Convert raw ADC value to voltage
    float voltage = (float)analog_value * (measured_VDD / 4095.0);

    return voltage; 
}

void potentiometry(unsigned long duration_ms, unsigned long sample_interval_ms) {
    unsigned long startTime = millis();
    unsigned long currentTime = startTime;
    unsigned long nextSampleTime = startTime; // Time for the next sample

    Serial.println("Starting Potentiometry Measurement...");

    while (currentTime - startTime < duration_ms) {
        currentTime = millis();

        // Sample at the defined interval
        if (currentTime >= nextSampleTime) {
            float voltage = read_lmp91200_pot_voltage();
            float vout_reading = analogRead(LMP91200_VOUT_PIN)* (measured_VDD / 4095.0);
            float vocm_reading = analogRead(LMP91200_VOCM_PIN)* (measured_VDD / 4095.0);
            unsigned long elapsedTime = currentTime - startTime;

            // Print to Serial Monitor
            Serial.print(elapsedTime);
            Serial.print(",");
            Serial.print(vout_reading,3);
            Serial.print(",");
            Serial.print(vocm_reading,3);
            Serial.print(",");
            Serial.println(voltage, 4); // Print with more precision if desired

            // Send data via BLE
            send_pot_data_to_app(vocm_reading, vout_reading); // Send elapsed time as float

            nextSampleTime = currentTime + sample_interval_ms; // Schedule next sample
        }
         // Allow other tasks to run, especially BLE handling
         // A small delay helps prevent hogging the CPU
        delay(5); // Adjust as needed, but keep it short
    }
     Serial.println("Potentiometry Measurement Complete.");
}

void cv1(float end_voltage, float voltage_step, unsigned long cv_delay_ms, int direction) {
    float current_voltage = 1.5;
    uint8_t bias_bin_list[] = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14};
    float bias_list[] = {0,0.01,0.02,0.04,0.06,0.08,0.10,0.12,0.14,0.16,0.18,0.20,0.22,0.24,1};

    uint8_t bias_sign = 0b1; // positive bias
    if (direction==1){
      bias_sign = 0b0;
    }

    int bias_count = 0;
    int bias_list_length = sizeof(bias_list)/sizeof(bias_list[0]);
    lmp91000_write_register(LMP91000_LOCK_REG, 0x00);
    uint8_t refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    refcn = (refcn & 0b11110000) | (bias_bin_list[bias_count] & 0b00001111);  // bias 0
    refcn = (refcn & 0b11101111) | ((bias_sign & 0b00000001) << 4); // Set bias sign (bit 4)
    lmp91000_write_register(LMP91000_REFCN_REG, refcn);

    uint16_t dac_value = (uint16_t)((current_voltage / measured_VDD) * 4095.0);
    dac_value = constrain(dac_value, 0, 4095);
    mcp4725_set_voltage(dac_value);
    dac_value_global = dac_value;

    unsigned long adjusted_delay = cv_delay_ms*0.015/voltage_step;
    adjusted_delay = max(cv_delay_ms, adjusted_delay*2);
    delay(cv_delay_ms);

    float vref = current_voltage;
    refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    float cell_voltage = calculate_cell_voltage(vref, refcn);
    float vcell_abs = abs(cell_voltage);
    long vcell_abs_mV = long(vcell_abs*1000+0.5);
    float lmp91000_vout = read_lmp91000_voltage();
    float vout_minusIZ = lmp91000_vout - vref/2;

    unsigned long currentTime = millis();
    unsigned long stepTime = currentTime - previousTime;
    previousTime = currentTime;

    Serial.print(vref, 3); Serial.print(",");
    Serial.print(lmp91000_vout, 3); Serial.print(",");
    Serial.print(cell_voltage, 3); Serial.print(",");
    Serial.print(vout_minusIZ, 3); Serial.print(",");
    Serial.print(bias_list[bias_count]); Serial.print(",");
    Serial.print(refcn, BIN); Serial.print(",");
    Serial.println(stepTime);
    if (deviceConnected) {
      send_cv_data_to_app(vout_minusIZ, cell_voltage);
    }

    // if (vcell_abs_mV==15){
      // Serial.println(voltage_step);
      // Serial.println(cv_delay_ms);
      float dd = cv_delay_ms*0.015/voltage_step - cv_delay_ms;
      // Serial.println(dd);
      delay(dd);
      // delay(-cv_delay_ms*voltage_step/0.015 - cv_delay_ms);
    // }

    while (bias_count<bias_list_length && vref<measured_VDD && vcell_abs+voltage_step<=end_voltage){
      bias_count++;
      refcn = (refcn & 0b11110000) | (bias_bin_list[bias_count] & 0b00001111);
      vref = vcell_abs / bias_list[bias_count];
      vref = constrain(vref, 1.5, measured_VDD);
      uint16_t dac_value = (uint16_t)((vref / measured_VDD) * 4095.0);
      dac_value = constrain(dac_value, 0, 4095);
      mcp4725_set_voltage(dac_value);
      dac_value_global = dac_value;
      lmp91000_write_register(LMP91000_REFCN_REG, refcn);

      delay(10);

      while (vcell_abs+voltage_step<=1.5*bias_list[bias_count+1] && vcell_abs+voltage_step<end_voltage){
        vref += voltage_step/bias_list[bias_count];

        vref = constrain(vref, 1.5, measured_VDD);
        uint16_t dac_value = (uint16_t)((vref / measured_VDD) * 4095.0);
        dac_value = constrain(dac_value, 0, 4095);
        mcp4725_set_voltage(dac_value);
        dac_value_global = dac_value;
        delay(cv_delay_ms);

        refcn = lmp91000_read_register(LMP91000_REFCN_REG);
        cell_voltage = calculate_cell_voltage(vref, refcn);
        vcell_abs = abs(cell_voltage);
        vcell_abs_mV = long(vcell_abs*1000+0.5);

        // if (vcell_abs_mV==15){
        //   // Serial.println(voltage_step);
        //   // Serial.println(cv_delay_ms);
        //   float dd = cv_delay_ms*0.015/voltage_step - cv_delay_ms;
        //   Serial.println(dd);
        //   delay(dd);
        //   // delay(-cv_delay_ms*voltage_step/0.015 - cv_delay_ms);
        // }

        lmp91000_vout = read_lmp91000_voltage();
        vout_minusIZ = lmp91000_vout - vref/2;

        currentTime = millis();
        stepTime = currentTime - previousTime;
        previousTime = currentTime;

        Serial.print(vref, 3); Serial.print(",");
        Serial.print(lmp91000_vout, 3); Serial.print(",");
        Serial.print(cell_voltage, 3); Serial.print(",");
        Serial.print(vout_minusIZ, 3); Serial.print(",");
        Serial.print(bias_list[bias_count]); Serial.print(",");
        Serial.print(refcn, BIN); Serial.print(",");
        Serial.println(stepTime);
        if (deviceConnected) {
          send_cv_data_to_app(vout_minusIZ, cell_voltage);
        }
      }
    }

    if (direction==1){
      delay(cv_delay_ms);
      if (deviceConnected) {
        send_cv_data_to_app(vout_minusIZ, cell_voltage,1);
      }
    }

    lmp91000_write_register(LMP91000_LOCK_REG, 0x01);
    actual_end_voltage = cell_voltage;
}

void cv2(float end_voltage, float voltage_step, unsigned long cv_delay_ms, int direction, int final_loop) {
    uint8_t bias_bin_list[] = {0,1,2,3,4,5,6,7,8,9,10,11,12,13};
    float bias_list[] = {0,0.01,0.02,0.04,0.06,0.08,0.10,0.12,0.14,0.16,0.18,0.20,0.22,0.24};

    uint8_t bias_sign = 0b1; // positive bias
    voltage_step = -voltage_step;
    if (direction==1){
      bias_sign = 0b0;
    }

    int bias_count = 1;
    int bias_list_length = sizeof(bias_list)/sizeof(bias_list[0]);
    lmp91000_write_register(LMP91000_LOCK_REG, 0x00);
    uint8_t refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    refcn = (refcn & 0b11110000) | (bias_bin_list[bias_list_length-bias_count] & 0b00001111);  // bias
    refcn = (refcn & 0b11101111) | ((bias_sign & 0b00000001) << 4); // Set bias sign (bit 4)
    lmp91000_write_register(LMP91000_REFCN_REG, refcn);

    float current_voltage = actual_end_voltage/bias_list[bias_list_length-bias_count];

    uint16_t dac_value = (uint16_t)((current_voltage / measured_VDD) * 4095.0);
    dac_value = constrain(dac_value, 0, 4095);
    mcp4725_set_voltage(dac_value);
    dac_value_global = dac_value;

    float vref = current_voltage;
    refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    float cell_voltage = calculate_cell_voltage(vref, refcn);
    float vcell_abs = abs(cell_voltage);
    long vcell_abs_mV = long(vcell_abs*1000+0.5);

    while (bias_count<bias_list_length && vcell_abs_mV>15){
      refcn = (refcn & 0b11110000) | (bias_bin_list[bias_list_length-bias_count] & 0b00001111);
      vref = vcell_abs / bias_list[bias_list_length-bias_count];
      vref = constrain(vref, 1.5, measured_VDD);
      uint16_t dac_value = (uint16_t)((vref / measured_VDD) * 4095.0);
      dac_value = constrain(dac_value, 0, 4095);
      mcp4725_set_voltage(dac_value);
      dac_value_global = dac_value;
      lmp91000_write_register(LMP91000_REFCN_REG, refcn);

      delay(10);

      while ((vcell_abs+voltage_step>=1.5*bias_list[bias_list_length-bias_count]) && (vcell_abs_mV>15)){
        vref += voltage_step/bias_list[bias_list_length-bias_count];
        vref = constrain(vref, 1.5, measured_VDD);
        uint16_t dac_value = (uint16_t)((vref / measured_VDD) * 4095.0);
        dac_value = constrain(dac_value, 0, 4095);
        mcp4725_set_voltage(dac_value);
        dac_value_global = dac_value;
        delay(cv_delay_ms);
        refcn = lmp91000_read_register(LMP91000_REFCN_REG);
        cell_voltage = calculate_cell_voltage(vref, refcn);
        vcell_abs = abs(cell_voltage);
        vcell_abs_mV = long(vcell_abs*1000+0.5);

        // if (vcell_abs_mV==15){
        //   // Serial.println(voltage_step);
        //   // Serial.println(cv_delay_ms);
        //   float dd = cv_delay_ms*(-voltage_step)/0.015 + cv_delay_ms;
        //   // Serial.println(dd);
        //   delay(dd);
        //   // delay(-cv_delay_ms*voltage_step/0.015 - cv_delay_ms);
        // }

        float lmp91000_vout = read_lmp91000_voltage();
        float vout_minusIZ = lmp91000_vout - vref/2;
        unsigned long currentTime = millis(); // Changed to unsigned long
        unsigned long stepTime = currentTime - previousTime; // Changed to unsigned long
        previousTime = currentTime;

        Serial.print(vref, 3); Serial.print(",");
        Serial.print(lmp91000_vout, 3); Serial.print(",");
        Serial.print(cell_voltage, 3); Serial.print(",");
        Serial.print(vout_minusIZ, 3); Serial.print(",");
        Serial.print(bias_list[bias_list_length-bias_count]); Serial.print(",");
        Serial.print(refcn, BIN); Serial.print(",");
        Serial.println(stepTime);
        if (deviceConnected) {
          send_cv_data_to_app(vout_minusIZ, cell_voltage);
        }

        if (vcell_abs_mV==15){
          // Serial.println(voltage_step);
          // Serial.println(cv_delay_ms);
          float dd = cv_delay_ms*0.015/(-voltage_step) - cv_delay_ms;
          Serial.println(dd);
          delay(dd);
          // delay(-cv_delay_ms*voltage_step/0.015 - cv_delay_ms);
        }
        
      }
      bias_count++;
    }

    if (final_loop==1){
      refcn = (refcn & 0b11110000) | (0b0000 & 0b00001111);
      lmp91000_write_register(LMP91000_REFCN_REG, refcn);
      delay(-cv_delay_ms*voltage_step/0.015);
      refcn = lmp91000_read_register(LMP91000_REFCN_REG);
      cell_voltage = calculate_cell_voltage(vref, refcn);
      float lmp91000_vout = read_lmp91000_voltage();
      float vout_minusIZ = lmp91000_vout - vref/2;

      unsigned long currentTime = millis(); // Changed to unsigned long
      unsigned long stepTime = currentTime - previousTime; // Changed to unsigned long
      previousTime = currentTime;

      Serial.print(vref, 3); Serial.print(",");
      Serial.print(lmp91000_vout, 3); Serial.print(",");
      Serial.print(cell_voltage, 3); Serial.print(",");
      Serial.print(vout_minusIZ, 3); Serial.print(",");
      Serial.print(bias_list[bias_list_length-bias_count]); Serial.print(",");
      Serial.print(refcn, BIN); Serial.print(",");
      Serial.println(stepTime);
      if (deviceConnected) {
        send_cv_data_to_app(vout_minusIZ, cell_voltage);
      }
    }

    lmp91000_write_register(LMP91000_LOCK_REG, 0x01);
}

void cv(float lower_limit, float upper_limit, float sweep_rate, int n_loop, unsigned long cv_delay_ms){
  float voltage_step = sweep_rate*cv_delay_ms/1000;
  previousTime = millis();
  if (lower_limit>0 && upper_limit>0){
    for (int i=0;i<n_loop;i++){
      cv_nonzero1(lower_limit, upper_limit, voltage_step, cv_delay_ms, 0);
      cv_nonzero2(actual_end_voltage, lower_limit, voltage_step, cv_delay_ms, 0);
    }
  }
  else if (lower_limit<0 && upper_limit<0){
    for (int i=0;i<n_loop;i++){
      cv_nonzero2(lower_limit, upper_limit, voltage_step, cv_delay_ms, 1);
      cv_nonzero1(actual_end_voltage, lower_limit, voltage_step, cv_delay_ms, 1);
    }
  }
  else {
    int numberOfSteps = floor((lower_limit - 0.015) / voltage_step);
    actual_end_voltage = 0.015 + numberOfSteps * voltage_step;

    for (int i=0;i<n_loop;i++){
      alt_cv2(-lower_limit, voltage_step, cv_delay_ms, 1);
      alt_cv1(upper_limit, voltage_step, cv_delay_ms, 0);
      alt_cv2(upper_limit, voltage_step, cv_delay_ms, 0);
      alt_cv1(-lower_limit, voltage_step, cv_delay_ms, 1);
    }
  }
}

void alt_cv1(float end_voltage, float voltage_step, unsigned long cv_delay_ms, int direction){
    float current_voltage = 1.5;
    uint8_t bias_bin_list[] = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14};
    float bias_list[] = {0,0.01,0.02,0.04,0.06,0.08,0.10,0.12,0.14,0.16,0.18,0.20,0.22,0.24,1};

    uint8_t bias_sign = 0b1; // positive bias
    if (direction==1){
      bias_sign = 0b0;
    }

    int bias_count = 0;
    int bias_list_length = sizeof(bias_list)/sizeof(bias_list[0]);
    lmp91000_write_register(LMP91000_LOCK_REG, 0x00);
    uint8_t refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    refcn = (refcn & 0b11110000) | (bias_bin_list[bias_count] & 0b00001111);  // bias 0
    refcn = (refcn & 0b11101111) | ((bias_sign & 0b00000001) << 4); // Set bias sign (bit 4)
    lmp91000_write_register(LMP91000_REFCN_REG, refcn);

    uint16_t dac_value = (uint16_t)((current_voltage / measured_VDD) * 4095.0);
    dac_value = constrain(dac_value, 0, 4095);
    mcp4725_set_voltage(dac_value);
    dac_value_global = dac_value;

    unsigned long adjusted_delay = cv_delay_ms*0.015/voltage_step;
    adjusted_delay = max(cv_delay_ms, adjusted_delay*2);
    delay(cv_delay_ms);

    float vref = current_voltage;
    refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    float cell_voltage = calculate_cell_voltage(vref, refcn);
    float vcell_abs = abs(cell_voltage);
    long vcell_abs_mV = long(vcell_abs*1000+0.5);
    float lmp91000_vout = read_lmp91000_voltage();
    float vout_minusIZ = lmp91000_vout - vref/2;

    unsigned long currentTime = millis();
    unsigned long stepTime = currentTime - previousTime;
    previousTime = currentTime;

    Serial.print(vref, 3); Serial.print(",");
    Serial.print(lmp91000_vout, 3); Serial.print(",");
    Serial.print(cell_voltage, 3); Serial.print(",");
    Serial.print(vout_minusIZ, 3); Serial.print(",");
    Serial.print(bias_list[bias_count]); Serial.print(",");
    Serial.print(refcn, BIN); Serial.print(",");
    Serial.println(stepTime);
    if (deviceConnected) {
      send_cv_data_to_app(vout_minusIZ, cell_voltage);
    }

    while (bias_count<bias_list_length && vcell_abs+voltage_step<=end_voltage && measured_VDD*bias_list[bias_count]<=end_voltage){
      bias_count++;
      refcn = (refcn & 0b11110000) | (bias_bin_list[bias_count] & 0b00001111);
      lmp91000_write_register(LMP91000_REFCN_REG, refcn);
      delay(cv_delay_ms);

      cell_voltage = calculate_cell_voltage(vref, refcn);
      vcell_abs = abs(cell_voltage);
      vcell_abs_mV = long(vcell_abs*1000+0.5);

      lmp91000_vout = read_lmp91000_voltage();
      vout_minusIZ = lmp91000_vout - vref/2;

      currentTime = millis();
      stepTime = currentTime - previousTime;
      previousTime = currentTime;

      Serial.print(vref, 3); Serial.print(",");
      Serial.print(lmp91000_vout, 3); Serial.print(",");
      Serial.print(cell_voltage, 3); Serial.print(",");
      Serial.print(vout_minusIZ, 3); Serial.print(",");
      Serial.print(bias_list[bias_count]); Serial.print(",");
      Serial.print(refcn, BIN); Serial.print(",");
      Serial.println(stepTime);
      if (deviceConnected) {
        send_cv_data_to_app(vout_minusIZ, cell_voltage);
      }
    }


    while (vcell_abs+voltage_step<end_voltage){
      vref += voltage_step/bias_list[bias_count];

      vref = constrain(vref, 1.5, measured_VDD);
      uint16_t dac_value = (uint16_t)((vref / measured_VDD) * 4095.0);
      dac_value = constrain(dac_value, 0, 4095);
      mcp4725_set_voltage(dac_value);
      dac_value_global = dac_value;
      delay(cv_delay_ms);

      refcn = lmp91000_read_register(LMP91000_REFCN_REG);
      cell_voltage = calculate_cell_voltage(vref, refcn);
      vcell_abs = abs(cell_voltage);
      vcell_abs_mV = long(vcell_abs*1000+0.5);

      lmp91000_vout = read_lmp91000_voltage();
      vout_minusIZ = lmp91000_vout - vref/2;

      currentTime = millis();
      stepTime = currentTime - previousTime;
      previousTime = currentTime;

      Serial.print(vref, 3); Serial.print(",");
      Serial.print(lmp91000_vout, 3); Serial.print(",");
      Serial.print(cell_voltage, 3); Serial.print(",");
      Serial.print(vout_minusIZ, 3); Serial.print(",");
      Serial.print(bias_list[bias_count]); Serial.print(",");
      Serial.print(refcn, BIN); Serial.print(",");
      Serial.println(stepTime);
      if (deviceConnected) {
        send_cv_data_to_app(vout_minusIZ, cell_voltage);
      }
    }

    if (direction==1){// let the app know one loop is completed
      delay(cv_delay_ms);
      if (deviceConnected) {
        send_cv_data_to_app(vout_minusIZ, cell_voltage,1);
      }
    }

    lmp91000_write_register(LMP91000_LOCK_REG, 0x01);
}

void alt_cv2(float end_voltage, float voltage_step, unsigned long cv_delay_ms, int direction, int final_loop) {
    uint8_t bias_bin_list[] = {0,1,2,3,4,5,6,7,8,9,10,11,12,13};
    float bias_list[] = {0,0.01,0.02,0.04,0.06,0.08,0.10,0.12,0.14,0.16,0.18,0.20,0.22,0.24};

    uint8_t bias_sign = 0b1; // positive bias
    voltage_step = -voltage_step;
    if (direction==1){
      bias_sign = 0b0;
    }

    int bias_count = 1;
    int bias_list_length = sizeof(bias_list)/sizeof(bias_list[0]);

    actual_end_voltage = abs(actual_end_voltage);

    while(bias_list[bias_list_length-bias_count]*measured_VDD>actual_end_voltage){
      bias_count++;
    }
    bias_count--;

    lmp91000_write_register(LMP91000_LOCK_REG, 0x00);
    uint8_t refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    refcn = (refcn & 0b11110000) | (bias_bin_list[bias_list_length-bias_count] & 0b00001111);  // bias
    refcn = (refcn & 0b11101111) | ((bias_sign & 0b00000001) << 4); // Set bias sign (bit 4)
    lmp91000_write_register(LMP91000_REFCN_REG, refcn);

    float current_voltage = actual_end_voltage/bias_list[bias_list_length-bias_count];

    uint16_t dac_value = (uint16_t)((current_voltage / measured_VDD) * 4095.0);
    dac_value = constrain(dac_value, 0, 4095);
    mcp4725_set_voltage(dac_value);
    dac_value_global = dac_value;

    float vref = current_voltage;
    refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    float cell_voltage = calculate_cell_voltage(vref, refcn);
    float vcell_abs = abs(cell_voltage);
    long vcell_abs_mV = long(vcell_abs*1000+0.5);


    while ((vcell_abs+voltage_step>=1.5*bias_list[bias_list_length-bias_count]) && (vcell_abs_mV>15)){
      vref += voltage_step/bias_list[bias_list_length-bias_count];
      vref = constrain(vref, 1.5, measured_VDD);
      uint16_t dac_value = (uint16_t)((vref / measured_VDD) * 4095.0);
      dac_value = constrain(dac_value, 0, 4095);
      mcp4725_set_voltage(dac_value);
      dac_value_global = dac_value;
      delay(cv_delay_ms);
      refcn = lmp91000_read_register(LMP91000_REFCN_REG);
      cell_voltage = calculate_cell_voltage(vref, refcn);
      vcell_abs = abs(cell_voltage);
      vcell_abs_mV = long(vcell_abs*1000+0.5);

      float lmp91000_vout = read_lmp91000_voltage();
      float vout_minusIZ = lmp91000_vout - vref/2;
      unsigned long currentTime = millis(); // Changed to unsigned long
      unsigned long stepTime = currentTime - previousTime; // Changed to unsigned long
      previousTime = currentTime;

      Serial.print(vref, 3); Serial.print(",");
      Serial.print(lmp91000_vout, 3); Serial.print(",");
      Serial.print(cell_voltage, 3); Serial.print(",");
      Serial.print(vout_minusIZ, 3); Serial.print(",");
      Serial.print(bias_list[bias_list_length-bias_count]); Serial.print(",");
      Serial.print(refcn, BIN); Serial.print(",");
      Serial.println(stepTime);
      if (deviceConnected) {
        send_cv_data_to_app(vout_minusIZ, cell_voltage);
      }

      if (vcell_abs_mV==15){
        float dd = cv_delay_ms*0.015/(-voltage_step) - cv_delay_ms;
        Serial.println(dd);
      }
      
    }

    while (bias_count<bias_list_length && vcell_abs_mV>15 && bias_count<bias_list_length){
      refcn = (refcn & 0b11110000) | (bias_bin_list[bias_list_length-bias_count] & 0b00001111);
      vref = 1.5;
      lmp91000_write_register(LMP91000_REFCN_REG, refcn);
      delay(cv_delay_ms);

      cell_voltage = calculate_cell_voltage(vref, refcn);
      vcell_abs = abs(cell_voltage);
      vcell_abs_mV = long(vcell_abs*1000+0.5);

      float lmp91000_vout = read_lmp91000_voltage();
      float vout_minusIZ = lmp91000_vout - vref/2;

      unsigned long currentTime = millis();
      unsigned long stepTime = currentTime - previousTime;
      previousTime = currentTime;

      Serial.print(vref, 3); Serial.print(",");
      Serial.print(lmp91000_vout, 3); Serial.print(",");
      Serial.print(cell_voltage, 3); Serial.print(",");
      Serial.print(vout_minusIZ, 3); Serial.print(",");
      Serial.print(bias_list[bias_list_length-bias_count]); Serial.print(",");
      Serial.print(refcn, BIN); Serial.print(",");
      Serial.println(stepTime);
      if (deviceConnected) {
        send_cv_data_to_app(vout_minusIZ, cell_voltage);
      }
      bias_count++;
    }

    if (final_loop==1){
      refcn = (refcn & 0b11110000) | (0b0000 & 0b00001111);
      lmp91000_write_register(LMP91000_REFCN_REG, refcn);
      delay(-cv_delay_ms*voltage_step/0.015);
      refcn = lmp91000_read_register(LMP91000_REFCN_REG);
      cell_voltage = calculate_cell_voltage(vref, refcn);
      float lmp91000_vout = read_lmp91000_voltage();
      float vout_minusIZ = lmp91000_vout - vref/2;

      unsigned long currentTime = millis(); // Changed to unsigned long
      unsigned long stepTime = currentTime - previousTime; // Changed to unsigned long
      previousTime = currentTime;

      Serial.print(vref, 3); Serial.print(",");
      Serial.print(lmp91000_vout, 3); Serial.print(",");
      Serial.print(cell_voltage, 3); Serial.print(",");
      Serial.print(vout_minusIZ, 3); Serial.print(",");
      Serial.print(bias_list[bias_list_length-bias_count]); Serial.print(",");
      Serial.print(refcn, BIN); Serial.print(",");
      Serial.println(stepTime);
      if (deviceConnected) {
        send_cv_data_to_app(vout_minusIZ, cell_voltage);
      }
    }

    lmp91000_write_register(LMP91000_LOCK_REG, 0x01);
}

void cv_nonzero1(float start_voltage, float end_voltage, float voltage_step, unsigned long cv_delay_ms, int sign) {
    uint8_t bias_bin_list[] = {0,1,2,3,5,9,13};
    float bias_list[] = {0,0.01,0.02,0.04,0.08,0.16,0.24};
    start_voltage = abs(start_voltage);
    end_voltage = abs(end_voltage);
    long end_voltage_mV = long(end_voltage*1000+0.5);
    long voltage_step_mV = long(voltage_step*1000+0.5);

    uint8_t bias_sign = 0b1; // positive bias
    if (sign==1){
      bias_sign = 0b0;
    }

    int bias_list_length = sizeof(bias_list)/sizeof(bias_list[0]);
    int bias_count = bias_list_length-1;

    while (bias_list[bias_count]*1.5>start_voltage && bias_count>0){
      bias_count--;
    }

    float current_voltage = start_voltage/bias_list[bias_count];

    lmp91000_write_register(LMP91000_LOCK_REG, 0x00);
    uint8_t refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    refcn = (refcn & 0b11110000) | (bias_bin_list[bias_count] & 0b00001111);  // bias
    refcn = (refcn & 0b11101111) | ((bias_sign & 0b00000001) << 4); // Set bias sign (bit 4)
    lmp91000_write_register(LMP91000_REFCN_REG, refcn);

    uint16_t dac_value = (uint16_t)((current_voltage / measured_VDD) * 4095.0);
    dac_value = constrain(dac_value, 0, 4095);
    mcp4725_set_voltage(dac_value);
    dac_value_global = dac_value;
    delay(cv_delay_ms);

    float vref = current_voltage;
    refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    float cell_voltage = calculate_cell_voltage(vref, refcn);
    float vcell_abs = abs(cell_voltage);
    long vcell_abs_mV = long(vcell_abs*1000+0.5);
    float lmp91000_vout = read_lmp91000_voltage();
    float vout_minusIZ = lmp91000_vout - vref/2;

    unsigned long currentTime = millis();
    unsigned long stepTime = currentTime - previousTime;
    previousTime = currentTime;

    Serial.print(vref, 3); Serial.print(",");
    Serial.print(lmp91000_vout, 3); Serial.print(",");
    Serial.print(cell_voltage, 3); Serial.print(",");
    Serial.print(vout_minusIZ, 3); Serial.print(",");
    Serial.print(bias_list[bias_count]); Serial.print(",");
    Serial.print(refcn, BIN); Serial.print(",");
    Serial.println(stepTime);
    if (deviceConnected) {
      send_cv_data_to_app(vout_minusIZ, cell_voltage);
    }

    while (bias_count<bias_list_length && vcell_abs_mV+voltage_step_mV*2<=end_voltage_mV && vref<measured_VDD){
      refcn = (refcn & 0b11110000) | (bias_bin_list[bias_count] & 0b00001111);
      lmp91000_write_register(LMP91000_REFCN_REG, refcn);
      vref = vcell_abs / bias_list[bias_count];

      while (vcell_abs+voltage_step<measured_VDD*bias_list[bias_count] && vcell_abs_mV+voltage_step_mV*2<=end_voltage_mV){
        vref += voltage_step/bias_list[bias_count];
        vref = constrain(vref, 1.5, measured_VDD);

        uint16_t dac_value = (uint16_t)((vref / measured_VDD) * 4095.0);
        dac_value = constrain(dac_value, 0, 4095);
        mcp4725_set_voltage(dac_value);
        dac_value_global = dac_value;
        delay(cv_delay_ms);

        refcn = lmp91000_read_register(LMP91000_REFCN_REG);
        cell_voltage = calculate_cell_voltage(vref, refcn);
        vcell_abs = abs(cell_voltage);
        vcell_abs_mV = long(vcell_abs*1000+0.5);

        lmp91000_vout = read_lmp91000_voltage();
        vout_minusIZ = lmp91000_vout - vref/2;

        currentTime = millis();
        stepTime = currentTime - previousTime;
        previousTime = currentTime;

        Serial.print(vref, 3); Serial.print(",");
        Serial.print(lmp91000_vout, 3); Serial.print(",");
        Serial.print(cell_voltage, 3); Serial.print(",");
        Serial.print(vout_minusIZ, 3); Serial.print(",");
        Serial.print(bias_list[bias_count]); Serial.print(",");
        Serial.print(refcn, BIN); Serial.print(",");
        Serial.println(stepTime);
        if (deviceConnected) {
          send_cv_data_to_app(vout_minusIZ, cell_voltage);
        }
      }
      bias_count++;
    }

    lmp91000_write_register(LMP91000_LOCK_REG, 0x01);
    actual_end_voltage = cell_voltage+voltage_step;
}

void cv_nonzero2(float start_voltage, float end_voltage, float voltage_step, unsigned long cv_delay_ms, int sign) {
    uint8_t bias_bin_list[] = {0,1,2,3,5,9,13};
    float bias_list[] = {0,0.01,0.02,0.04,0.08,0.16,0.24};
    start_voltage = abs(start_voltage);
    end_voltage = abs(end_voltage);
    long end_voltage_mV = long(end_voltage*1000+0.5);
    long voltage_step_mV = long(voltage_step*1000+0.5);

    uint8_t bias_sign = 0b1; // positive bias
    if (sign==1){
      bias_sign = 0b0;
    }

    int bias_list_length = sizeof(bias_list)/sizeof(bias_list[0]);
    int bias_count = 1;

    while ((bias_list[bias_count]*measured_VDD<abs(start_voltage)) && (bias_count<bias_list_length)){
      bias_count++;
    }

    float current_voltage = abs(start_voltage)/bias_list[bias_count];

    lmp91000_write_register(LMP91000_LOCK_REG, 0x00);
    uint8_t refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    refcn = (refcn & 0b11110000) | (bias_bin_list[bias_count] & 0b00001111);  // bias
    refcn = (refcn & 0b11101111) | ((bias_sign & 0b00000001) << 4); // Set bias sign (bit 4)
    lmp91000_write_register(LMP91000_REFCN_REG, refcn);

    uint16_t dac_value = (uint16_t)((current_voltage / measured_VDD) * 4095.0);
    dac_value = constrain(dac_value, 0, 4095);
    mcp4725_set_voltage(dac_value);
    dac_value_global = dac_value;
    delay(cv_delay_ms);

    float vref = current_voltage;
    refcn = lmp91000_read_register(LMP91000_REFCN_REG);
    float cell_voltage = calculate_cell_voltage(vref, refcn);
    float vcell_abs = abs(cell_voltage);
    long vcell_abs_mV = long(vcell_abs*1000+0.5);
    float lmp91000_vout = read_lmp91000_voltage();
    float vout_minusIZ = lmp91000_vout - vref/2;

    unsigned long currentTime = millis();
    unsigned long stepTime = currentTime - previousTime;
    previousTime = currentTime;

    Serial.print(vref, 3); Serial.print(",");
    Serial.print(lmp91000_vout, 3); Serial.print(",");
    Serial.print(cell_voltage, 3); Serial.print(",");
    Serial.print(vout_minusIZ, 3); Serial.print(",");
    Serial.print(bias_list[bias_count]); Serial.print(",");
    Serial.print(refcn, BIN); Serial.print(",");
    Serial.println(stepTime);
    if (deviceConnected) {
      send_cv_data_to_app(vout_minusIZ, cell_voltage);
    }

    while (bias_count>0 && vcell_abs_mV-voltage_step_mV*2>=end_voltage_mV && vref>=1.5){
      refcn = (refcn & 0b11110000) | (bias_bin_list[bias_count] & 0b00001111);
      lmp91000_write_register(LMP91000_REFCN_REG, refcn);
      vref = vcell_abs / bias_list[bias_count];

      while (vcell_abs-voltage_step>1.5*bias_list[bias_count] && vcell_abs_mV-voltage_step_mV*2>=end_voltage_mV){
        vref -= voltage_step/bias_list[bias_count];
        vref = constrain(vref, 1.5, measured_VDD);

        uint16_t dac_value = (uint16_t)((vref / measured_VDD) * 4095.0);
        dac_value = constrain(dac_value, 0, 4095);
        mcp4725_set_voltage(dac_value);
        dac_value_global = dac_value;
        delay(cv_delay_ms);

        refcn = lmp91000_read_register(LMP91000_REFCN_REG);
        cell_voltage = calculate_cell_voltage(vref, refcn);
        vcell_abs = abs(cell_voltage);
        vcell_abs_mV = long(vcell_abs*1000+0.5);

        lmp91000_vout = read_lmp91000_voltage();
        vout_minusIZ = lmp91000_vout - vref/2;

        currentTime = millis();
        stepTime = currentTime - previousTime;
        previousTime = currentTime;

        Serial.print(vref, 3); Serial.print(",");
        Serial.print(lmp91000_vout, 3); Serial.print(",");
        Serial.print(cell_voltage, 3); Serial.print(",");
        Serial.print(vout_minusIZ, 3); Serial.print(",");
        Serial.print(bias_list[bias_count]); Serial.print(",");
        Serial.print(refcn, BIN); Serial.print(",");
        Serial.println(stepTime);
        if (deviceConnected) {
          send_cv_data_to_app(vout_minusIZ, cell_voltage);
        }
      }
      bias_count--;
    }

    lmp91000_write_register(LMP91000_LOCK_REG, 0x01);
    actual_end_voltage = cell_voltage-voltage_step;
}