/**************************************************************

    "iSpindel"
    All rights reserverd by S.Lang <universam@web.de>

 **************************************************************/

#ifndef _GLOBALS_H
#define _GLOBALS_H

#pragma once
//#define USE_DMP false
#include <Arduino.h>
#include <Hash.h>

#include <Ticker.h>

#include <I2Cdev.h>
#include <MPU6050.h>
extern Ticker flasher;

// defines go here
// local fork of upstream 7.3.3: config push (POLY/sleep/SSID+PSK/fw OTA),
// credential ladder, rescue portal — bumped so OTA ver guards can't no-op
#define FIRMWAREVERSION "7.3.4"

#define API_FHEM true
#define API_UBIDOTS true
#define API_GENERIC true
#define API_TCONTROL true
#define API_INFLUXDB true
#define API_PROMETHEUS true
#define API_MQTT true
#define API_THINGSPEAK true
#define API_BLYNK true
#define API_BREWBLOX true
#define API_MQTT_HASSIO true
#define API_AWSIOTMQTT true //AWS
#define API_BRICKS true

//#define BLYNK_DEBUG
//#define APP_DEBUG
//#define BLYNK_PRINT Serial
#define BLYNK_NO_BUILTIN
#define BLYNK_NO_FANCY_LOGO
#define BLYNK_MAX_SENDBYTES 1200

#define TEMP_CELSIUS 0
#define TEMP_FAHRENHEIT 1
#define TEMP_KELVIN 2

#ifndef DEBUG
#define DEBUG true
#endif

#define CONSOLE(...)                                                                                                   \
  do                                                                                                                   \
  {                                                                                                                    \
    Serial.print(__VA_ARGS__);                                                                                         \
  } while (0)
#define CONSOLELN(...)                                                                                                 \
  do                                                                                                                   \
  {                                                                                                                    \
    Serial.println(__VA_ARGS__);                                                                                       \
  } while (0)

#define PORTALTIMEOUT 300

#define ADCDIVISOR 191.8
#define ONE_WIRE_BUS D6 // DS18B20 on ESP pin12
#define OW_PINS                                                                                                        \
  (const uint8_t[])                                                                                                    \
  {                                                                                                                    \
    D1, D6                                                                                                             \
  }
#define RESOLUTION 12 // 12bit resolution == 750ms update rate
#define OWinterval (760 / (1 << (12 - RESOLUTION)))
#define CFGFILE "/config.json"
#define TKIDSIZE 40
#define DNSSIZE 253

#define MEDIANROUNDSMAX 49
#define MEDIANROUNDSMIN 29
#define MEDIANAVRG MEDIANROUNDSMIN
#define MEDIAN_MAX_SIZE MEDIANROUNDSMAX

#define CBP_ENDPOINT "/api/hydrometer/v1/data"

#define DTUbiDots 0
#define DTThingspeak 1
#define DTCraftBeerPi 2
#define DTHTTP 3
#define DTTcontrol 4
#define DTFHEM 5
#define DTTCP 6
#define DTiSPINDELde 7
#define DTInfluxDB 8
#define DTPrometheus 9
#define DTMQTT 10
#define DTTHINGSPEAK 11
#define DTBLYNK 12
#define DTBREWBLOX 13
#define DTAWSIOTMQTT 14 //AWS
#define DTHTTPS 15
#define DTBRICKS 16

// Number of seconds after reset during which a
// subseqent reset will be considered a double reset.
#define DRD_TIMEOUT 1
// RTC Memory Address for the DoubleResetDetector to use
#define DRD_ADDRESS 0

#define WIFIENADDR 1
#define RTCVALIDFLAG 0xCAFEBABE

// sleep management
#define RTCSLEEPADDR 5
// credential-ladder state, one 32-bit RTC word each (see setup()): +2
// connFails, +3 pendFails, +4 portalNext, +5 portalGap, +6 refusedFlag,
// +7 otaAckFlag (0 none, 1 attempted and failed, 2 skipped)
#define RTCCONNFAILADDR (RTCSLEEPADDR + 2)
#define RTCPENDFAILADDR (RTCSLEEPADDR + 3)
#define RTCPORTALNEXTADDR (RTCSLEEPADDR + 4)
#define RTCPORTALGAPADDR (RTCSLEEPADDR + 5)
#define RTCREFUSEDADDR (RTCSLEEPADDR + 6)
#define RTCOTAACKADDR (RTCSLEEPADDR + 7)
#define MAXSLEEPTIME 3600UL //TODO
#define EMERGENCYSLEEP (myData.sleeptime * 3 < MAXSLEEPTIME ? MAXSLEEPTIME : myData.sleeptime * 3)
#define LOWBATT 3.3

// rescue-portal ladder: both the saved and any pushed credentials failing.
// The portal only opens when the saved SSID is still visible with a clear
// signal (a credentials problem); out of range / router off stays silent.
#define PORTAL_FIRST_CONNFAILS 10 // failed wakes before the first rescue portal
#define PORTAL_RETRY_GAP 40       // next portal after this many more failed wakes (x4 each retry)
#define PORTAL_MAX_RSSI -75       // saved SSID must be at least this close for the portal

// remote OTA: a cfg push carrying an fw object (url + md5 + ver) attempts the
// update in the wake that receives it — but only when the link can actually
// carry a ~600 kB download; at the fringe it acks "skip" instead
#define OTA_MIN_RSSI -75

#define UNINIT 0

extern int16_t ax, ay, az;
extern float Volt, Temperatur, Tilt, Gravity;

extern MPU6050 accelgyro;
extern bool saveConfig();
extern bool saveConfig(int16_t Offset[6]);
extern bool formatLittleFS();
extern void flash();
extern float calibrateToVref(float);

float scaleTemperatureFromC(float t, uint8_t);
String tempScaleLabel(uint8_t);

struct iData
{
  char token[TKIDSIZE * 2];
  char name[33] = "";
  char server[DNSSIZE];
  char uri[DNSSIZE];
  char username[TKIDSIZE];
  char password[DNSSIZE];
  char job[TKIDSIZE] = "ispindel";
  char instance[TKIDSIZE] = "000";
  char polynominal[1000] = "-0.00031*tilt^2+0.557*tilt-14.054";
  String ssid;
  String psk;
  // armed by a cfg push (SSID/PSK keys, one-shot from the proxy); tried per
  // wake before the saved SSID, promoted to ssid/psk after the first
  // successful POST, cleared after 3 failed wakes
  String pendingSsid;
  String pendingPsk;
  uint8_t api;
  uint32_t sleeptime = 15 * 60;
  uint16_t port = 80;
  uint32_t channel;
  float vfact = ADCDIVISOR;
  int16_t Offset[6];
  uint8_t tempscale = TEMP_CELSIUS;
  int8_t OWpin = -1;
#if API_MQTT_HASSIO
  bool hassio = false;
#endif
  bool usehttps = false;
};

extern iData myData;

#endif
