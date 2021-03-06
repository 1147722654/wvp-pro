package com.genersoft.iot.vmp.gb28181.transmit.request.impl;

import java.io.ByteArrayInputStream;
import java.text.ParseException;
import java.util.*;

import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.SipException;
import javax.sip.message.Request;
import javax.sip.message.Response;

import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetup;
import com.genersoft.iot.vmp.gb28181.bean.BaiduPoint;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceAlarm;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.MobilePosition;
import com.genersoft.iot.vmp.gb28181.bean.RecordInfo;
import com.genersoft.iot.vmp.gb28181.bean.RecordItem;
import com.genersoft.iot.vmp.gb28181.event.DeviceOffLineDetector;
import com.genersoft.iot.vmp.gb28181.event.EventPublisher;
import com.genersoft.iot.vmp.gb28181.transmit.callback.CheckForAllRecordsThread;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommander;
import com.genersoft.iot.vmp.gb28181.transmit.request.SIPRequestAbstractProcessor;
import com.genersoft.iot.vmp.gb28181.utils.DateUtil;
import com.genersoft.iot.vmp.gb28181.utils.NumericUtil;
import com.genersoft.iot.vmp.gb28181.utils.XmlUtil;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorager;
import com.genersoft.iot.vmp.utils.GpsUtil;
import com.genersoft.iot.vmp.utils.SpringBeanFactory;
import com.genersoft.iot.vmp.utils.redis.RedisUtil;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * @Description:MESSAGE???????????????
 * @author: swwheihei
 * @date: 2020???5???3??? ??????5:32:41
 */
@SuppressWarnings(value={"unchecked", "rawtypes"})
public class MessageRequestProcessor extends SIPRequestAbstractProcessor {

	public static volatile List<String> threadNameList = new ArrayList();

	private UserSetup userSetup = (UserSetup) SpringBeanFactory.getBean("userSetup");

	private final static Logger logger = LoggerFactory.getLogger(MessageRequestProcessor.class);

	private SIPCommander cmder;

	private IVideoManagerStorager storager;

	private IRedisCatchStorage redisCatchStorage;

	private EventPublisher publisher;

	private RedisUtil redis;

	private DeferredResultHolder deferredResultHolder;

	private DeviceOffLineDetector offLineDetector;

	private final static String CACHE_RECORDINFO_KEY = "CACHE_RECORDINFO_";

	private static final String MESSAGE_KEEP_ALIVE = "Keepalive";
	private static final String MESSAGE_CONFIG_DOWNLOAD = "ConfigDownload";
	private static final String MESSAGE_CATALOG = "Catalog";
	private static final String MESSAGE_DEVICE_INFO = "DeviceInfo";
	private static final String MESSAGE_ALARM = "Alarm";
	private static final String MESSAGE_RECORD_INFO = "RecordInfo";
	private static final String MESSAGE_MEDIA_STATUS = "MediaStatus";
	// private static final String MESSAGE_BROADCAST = "Broadcast";
	private static final String MESSAGE_DEVICE_STATUS = "DeviceStatus";
	private static final String MESSAGE_DEVICE_CONTROL = "DeviceControl";
	private static final String MESSAGE_DEVICE_CONFIG = "DeviceConfig";
	private static final String MESSAGE_MOBILE_POSITION = "MobilePosition";
	// private static final String MESSAGE_MOBILE_POSITION_INTERVAL = "Interval";
	private static final String MESSAGE_PRESET_QUERY = "PresetQuery";

	/**
	 * ??????MESSAGE??????
	 * 
	 * @param evt
	 */
	@Override
	public void process(RequestEvent evt) {

		try {
			Element rootElement = getRootElement(evt);
			String cmd = XmlUtil.getText(rootElement, "CmdType");

			if (MESSAGE_KEEP_ALIVE.equals(cmd)) {
				logger.info("?????????KeepAlive??????");
				processMessageKeepAlive(evt);
			} else if (MESSAGE_CONFIG_DOWNLOAD.equals(cmd)) {
				logger.info("?????????ConfigDownload??????");
				processMessageConfigDownload(evt);
			} else if (MESSAGE_CATALOG.equals(cmd)) {
				logger.info("?????????Catalog??????");
				processMessageCatalogList(evt);
			} else if (MESSAGE_DEVICE_INFO.equals(cmd)) {
				logger.info("?????????DeviceInfo??????");
				processMessageDeviceInfo(evt);
			} else if (MESSAGE_DEVICE_STATUS.equals(cmd)) {
				logger.info("?????????DeviceStatus??????");
				processMessageDeviceStatus(evt);
			} else if (MESSAGE_DEVICE_CONTROL.equals(cmd)) {
				logger.info("?????????DeviceControl??????");
				processMessageDeviceControl(evt);
			} else if (MESSAGE_DEVICE_CONFIG.equals(cmd)) {
				logger.info("?????????DeviceConfig??????");
				processMessageDeviceConfig(evt);
			} else if (MESSAGE_ALARM.equals(cmd)) {
				logger.info("?????????Alarm??????");
				processMessageAlarm(evt);
			} else if (MESSAGE_RECORD_INFO.equals(cmd)) {
				logger.info("?????????RecordInfo??????");
				processMessageRecordInfo(evt);
			}else if (MESSAGE_MEDIA_STATUS.equals(cmd)) {
				logger.info("?????????MediaStatus??????");
				processMessageMediaStatus(evt);
			} else if (MESSAGE_MOBILE_POSITION.equals(cmd)) {
				logger.info("?????????MobilePosition??????");
				processMessageMobilePosition(evt);
			} else if (MESSAGE_PRESET_QUERY.equals(cmd)) {
				logger.info("?????????PresetQuery??????");
				processMessagePresetQuery(evt);
			} else {
				logger.info("??????????????????" + cmd);
				responseAck(evt);
			}
		} catch (DocumentException | SipException |InvalidArgumentException | ParseException e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????MobilePosition??????????????????
	 * 
	 * @param evt
	 */
	private void processMessageMobilePosition(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			MobilePosition mobilePosition = new MobilePosition();
			Element deviceIdElement = rootElement.element("DeviceID");
			String deviceId = deviceIdElement.getTextTrim().toString();
			Device device = storager.queryVideoDevice(deviceId);
			if (device != null) {
				if (!StringUtils.isEmpty(device.getName())) {
					mobilePosition.setDeviceName(device.getName());
				}
			}
			mobilePosition.setDeviceId(XmlUtil.getText(rootElement, "DeviceID"));
			mobilePosition.setTime(XmlUtil.getText(rootElement, "Time"));
			mobilePosition.setLongitude(Double.parseDouble(XmlUtil.getText(rootElement, "Longitude")));
			mobilePosition.setLatitude(Double.parseDouble(XmlUtil.getText(rootElement, "Latitude")));
            if (NumericUtil.isDouble(XmlUtil.getText(rootElement, "Speed"))) {
				mobilePosition.setSpeed(Double.parseDouble(XmlUtil.getText(rootElement, "Speed")));
			} else {
				mobilePosition.setSpeed(0.0);
			}
			if (NumericUtil.isDouble(XmlUtil.getText(rootElement, "Direction"))) {
				mobilePosition.setDirection(Double.parseDouble(XmlUtil.getText(rootElement, "Direction")));
			} else {
				mobilePosition.setDirection(0.0);
			}
			if (NumericUtil.isDouble(XmlUtil.getText(rootElement, "Altitude"))) {
				mobilePosition.setAltitude(Double.parseDouble(XmlUtil.getText(rootElement, "Altitude")));
			} else {
				mobilePosition.setAltitude(0.0);
			}
			mobilePosition.setReportSource("Mobile Position");
			BaiduPoint bp = new BaiduPoint();
			bp = GpsUtil.Wgs84ToBd09(String.valueOf(mobilePosition.getLongitude()), String.valueOf(mobilePosition.getLatitude()));
			logger.info("???????????????" + bp.getBdLng() + ", " + bp.getBdLat());
			mobilePosition.setGeodeticSystem("BD-09");
			mobilePosition.setCnLng(bp.getBdLng());
			mobilePosition.setCnLat(bp.getBdLat());
			if (!userSetup.getSavePositionHistory()) {
				storager.clearMobilePositionsByDeviceId(deviceId);
			}
			storager.insertMobilePosition(mobilePosition);
			//?????? 200 OK
			responseAck(evt);
		} catch (DocumentException | SipException | InvalidArgumentException | ParseException e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????DeviceStatus????????????Message
	 * 
	 * @param evt
	 */
	private void processMessageDeviceStatus(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			String deviceId = XmlUtil.getText(rootElement, "DeviceID");
			// ??????????????????????????? ?????????????????????
			if (storager.exists(deviceId)) {
				// ??????200 OK
				responseAck(evt);
				JSONObject json = new JSONObject();
				XmlUtil.node2Json(rootElement, json);
				if (logger.isDebugEnabled()) {
					logger.debug(json.toJSONString());
				}
				RequestMessage msg = new RequestMessage();
				msg.setDeviceId(deviceId);
				msg.setType(DeferredResultHolder.CALLBACK_CMD_DEVICESTATUS);
				msg.setData(json);
				deferredResultHolder.invokeResult(msg);

				if (offLineDetector.isOnline(deviceId)) {
					publisher.onlineEventPublish(deviceId, VideoManagerConstants.EVENT_ONLINE_KEEPLIVE);
				} else {
				}
			}
		} catch (ParseException | SipException | InvalidArgumentException | DocumentException e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????DeviceControl????????????Message
	 * 
	 * @param evt
	 */
	private void processMessageDeviceControl(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			String deviceId = XmlUtil.getText(rootElement, "DeviceID");
			//String result = XmlUtil.getText(rootElement, "Result");
			// ??????200 OK
			responseAck(evt);
			if (rootElement.getName().equals("Response")) {//} !XmlUtil.isEmpty(result)) {
				// ???????????????????????????DeviceControl???????????????
				JSONObject json = new JSONObject();
				XmlUtil.node2Json(rootElement, json);
				if (logger.isDebugEnabled()) {
					logger.debug(json.toJSONString());
				}
				RequestMessage msg = new RequestMessage();
				msg.setDeviceId(deviceId);
				msg.setType(DeferredResultHolder.CALLBACK_CMD_DEVICECONTROL);
				msg.setData(json);
				deferredResultHolder.invokeResult(msg);
			} else {
				// ????????????????????????DeviceControl??????
			}
		} catch (ParseException | SipException | InvalidArgumentException | DocumentException e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????DeviceConfig????????????Message
	 * 
	 * @param evt
	 */
	private void processMessageDeviceConfig(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			String deviceId = XmlUtil.getText(rootElement, "DeviceID");
			// ??????200 OK
			responseAck(evt);
			if (rootElement.getName().equals("Response")) {
					// ???????????????????????????DeviceControl???????????????
				JSONObject json = new JSONObject();
				XmlUtil.node2Json(rootElement, json);
				if (logger.isDebugEnabled()) {
					logger.debug(json.toJSONString());
				}
				RequestMessage msg = new RequestMessage();
				msg.setDeviceId(deviceId);
				msg.setType(DeferredResultHolder.CALLBACK_CMD_DEVICECONFIG);
				msg.setData(json);
				deferredResultHolder.invokeResult(msg);
			} else {
				// ????????????????????????DeviceConfig??????
			}
		} catch (ParseException | SipException | InvalidArgumentException | DocumentException e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????ConfigDownload????????????Message
	 * 
	 * @param evt
	 */
	private void processMessageConfigDownload(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			String deviceId = XmlUtil.getText(rootElement, "DeviceID");
			// ??????200 OK
			responseAck(evt);
			if (rootElement.getName().equals("Response")) {
					// ???????????????????????????DeviceControl???????????????
				JSONObject json = new JSONObject();
				XmlUtil.node2Json(rootElement, json);
				if (logger.isDebugEnabled()) {
					logger.debug(json.toJSONString());
				}
				RequestMessage msg = new RequestMessage();
				msg.setDeviceId(deviceId);
				msg.setType(DeferredResultHolder.CALLBACK_CMD_CONFIGDOWNLOAD);
				msg.setData(json);
				deferredResultHolder.invokeResult(msg);
			} else {
				// ????????????????????????DeviceConfig??????
			}
		} catch (ParseException | SipException | InvalidArgumentException | DocumentException e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????PresetQuery???????????????Message
	 * 
	 * @param evt
	 */
	private void processMessagePresetQuery(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			String deviceId = XmlUtil.getText(rootElement, "DeviceID");
			// ??????200 OK
			responseAck(evt);
			if (rootElement.getName().equals("Response")) {//   !XmlUtil.isEmpty(result)) {
				// ???????????????????????????DeviceControl???????????????
				JSONObject json = new JSONObject();
				XmlUtil.node2Json(rootElement, json);
				if (logger.isDebugEnabled()) {
					logger.debug(json.toJSONString());
				}
				RequestMessage msg = new RequestMessage();
				msg.setDeviceId(deviceId);
				msg.setType(DeferredResultHolder.CALLBACK_CMD_PRESETQUERY);
				msg.setData(json);
				deferredResultHolder.invokeResult(msg);
			} else {
				// ????????????????????????DeviceControl??????
			}
		} catch (ParseException | SipException | InvalidArgumentException | DocumentException e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????DeviceInfo????????????Message
	 * 
	 * @param evt
	 */
	private void processMessageDeviceInfo(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			Element deviceIdElement = rootElement.element("DeviceID");
			String deviceId = deviceIdElement.getTextTrim().toString();

			Device device = storager.queryVideoDevice(deviceId);
			if (device == null) {
				return;
			}
			device.setName(XmlUtil.getText(rootElement, "DeviceName"));
			device.setManufacturer(XmlUtil.getText(rootElement, "Manufacturer"));
			device.setModel(XmlUtil.getText(rootElement, "Model"));
			device.setFirmware(XmlUtil.getText(rootElement, "Firmware"));
			if (StringUtils.isEmpty(device.getStreamMode())) {
				device.setStreamMode("UDP");
			}
			storager.updateDevice(device);

			RequestMessage msg = new RequestMessage();
			msg.setDeviceId(deviceId);
			msg.setType(DeferredResultHolder.CALLBACK_CMD_DEVICEINFO);
			msg.setData(device);
			deferredResultHolder.invokeResult(msg);
			// ??????200 OK
			responseAck(evt);
			if (offLineDetector.isOnline(deviceId)) {
				publisher.onlineEventPublish(deviceId, VideoManagerConstants.EVENT_ONLINE_KEEPLIVE);
			}
		} catch (DocumentException | SipException | InvalidArgumentException | ParseException e) {
			e.printStackTrace();
		}
	}

	/***
	 * ??????catalog???????????????????????? ??????
	 * 
	 * @param evt
	 */
	private void processMessageCatalogList(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			Element deviceIdElement = rootElement.element("DeviceID");
			String deviceId = deviceIdElement.getText();
			Element deviceListElement = rootElement.element("DeviceList");
			if (deviceListElement == null) {
				return;
			}
			Iterator<Element> deviceListIterator = deviceListElement.elementIterator();
			if (deviceListIterator != null) {
				Device device = storager.queryVideoDevice(deviceId);
				if (device == null) {
					return;
				}
				// ??????DeviceList
				while (deviceListIterator.hasNext()) {
					Element itemDevice = deviceListIterator.next();
					Element channelDeviceElement = itemDevice.element("DeviceID");
					if (channelDeviceElement == null) {
						continue;
					}
					String channelDeviceId = channelDeviceElement.getTextTrim();
					Element channdelNameElement = itemDevice.element("Name");
					String channelName = channdelNameElement != null ? channdelNameElement.getTextTrim().toString() : "";
					Element statusElement = itemDevice.element("Status");
					String status = statusElement != null ? statusElement.getTextTrim().toString() : "ON";
					DeviceChannel deviceChannel = new DeviceChannel();
					deviceChannel.setName(channelName);
					deviceChannel.setChannelId(channelDeviceId);
					// ONLINE OFFLINE  HIKVISION DS-7716N-E4 NVR??????????????????
					if (status.equals("ON") || status.equals("On") || status.equals("ONLINE")) {
						deviceChannel.setStatus(1);
					}
					if (status.equals("OFF") || status.equals("Off") || status.equals("OFFLINE")) {
						deviceChannel.setStatus(0);
					}

					deviceChannel.setManufacture(XmlUtil.getText(itemDevice, "Manufacturer"));
					deviceChannel.setModel(XmlUtil.getText(itemDevice, "Model"));
					deviceChannel.setOwner(XmlUtil.getText(itemDevice, "Owner"));
					deviceChannel.setCivilCode(XmlUtil.getText(itemDevice, "CivilCode"));
					deviceChannel.setBlock(XmlUtil.getText(itemDevice, "Block"));
					deviceChannel.setAddress(XmlUtil.getText(itemDevice, "Address"));
					if (XmlUtil.getText(itemDevice, "Parental") == null || XmlUtil.getText(itemDevice, "Parental") == "") {
						deviceChannel.setParental(0);
					} else {
						deviceChannel.setParental(Integer.parseInt(XmlUtil.getText(itemDevice, "Parental")));
					} 
					deviceChannel.setParentId(XmlUtil.getText(itemDevice, "ParentID"));
					if (XmlUtil.getText(itemDevice, "SafetyWay") == null || XmlUtil.getText(itemDevice, "SafetyWay")== "") {
						deviceChannel.setSafetyWay(0);
					} else {
						deviceChannel.setSafetyWay(Integer.parseInt(XmlUtil.getText(itemDevice, "SafetyWay")));
					}
					if (XmlUtil.getText(itemDevice, "RegisterWay") == null || XmlUtil.getText(itemDevice, "RegisterWay") =="") {
						deviceChannel.setRegisterWay(1);
					} else {
						deviceChannel.setRegisterWay(Integer.parseInt(XmlUtil.getText(itemDevice, "RegisterWay")));
					}
					deviceChannel.setCertNum(XmlUtil.getText(itemDevice, "CertNum"));
					if (XmlUtil.getText(itemDevice, "Certifiable") == null || XmlUtil.getText(itemDevice, "Certifiable") == "") {
						deviceChannel.setCertifiable(0);
					} else {
						deviceChannel.setCertifiable(Integer.parseInt(XmlUtil.getText(itemDevice, "Certifiable")));
					}
					if (XmlUtil.getText(itemDevice, "ErrCode") == null || XmlUtil.getText(itemDevice, "ErrCode") == "") {
						deviceChannel.setErrCode(0);
					} else {
						deviceChannel.setErrCode(Integer.parseInt(XmlUtil.getText(itemDevice, "ErrCode")));
					}
					deviceChannel.setEndTime(XmlUtil.getText(itemDevice, "EndTime"));
					deviceChannel.setSecrecy(XmlUtil.getText(itemDevice, "Secrecy"));
					deviceChannel.setIpAddress(XmlUtil.getText(itemDevice, "IPAddress"));
					if (XmlUtil.getText(itemDevice, "Port") == null || XmlUtil.getText(itemDevice, "Port") =="") {
						deviceChannel.setPort(0);
					} else {
						deviceChannel.setPort(Integer.parseInt(XmlUtil.getText(itemDevice, "Port")));
					}
					deviceChannel.setPassword(XmlUtil.getText(itemDevice, "Password"));
					if (NumericUtil.isDouble(XmlUtil.getText(itemDevice, "Longitude"))) {
						deviceChannel.setLongitude(Double.parseDouble(XmlUtil.getText(itemDevice, "Longitude")));
					} else {
						deviceChannel.setLongitude(0.00);
					}
					if (NumericUtil.isDouble(XmlUtil.getText(itemDevice, "Latitude"))) {
						deviceChannel.setLatitude(Double.parseDouble(XmlUtil.getText(itemDevice, "Latitude")));
					} else {
						deviceChannel.setLatitude(0.00);
					}
					if (XmlUtil.getText(itemDevice, "PTZType") == null || XmlUtil.getText(itemDevice, "PTZType") == "") {
						deviceChannel.setPTZType(0);
					} else {
						deviceChannel.setPTZType(Integer.parseInt(XmlUtil.getText(itemDevice, "PTZType")));
					}
					deviceChannel.setHasAudio(true); // ???????????????????????????????????????????????????????????????AAC
					storager.updateChannel(device.getDeviceId(), deviceChannel);
				}

				RequestMessage msg = new RequestMessage();
				msg.setDeviceId(deviceId);
				msg.setType(DeferredResultHolder.CALLBACK_CMD_CATALOG);
				msg.setData(device);
				deferredResultHolder.invokeResult(msg);
				// ??????200 OK
				responseAck(evt);
				if (offLineDetector.isOnline(deviceId)) {
					publisher.onlineEventPublish(deviceId, VideoManagerConstants.EVENT_ONLINE_KEEPLIVE);
				}
			}
		} catch (DocumentException | SipException | InvalidArgumentException | ParseException e) {
			e.printStackTrace();
		}
	}

	/***
	 * alarm????????????????????????
	 * @param evt
	 */
	private void processMessageAlarm(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			Element deviceIdElement = rootElement.element("DeviceID");
			String deviceId = deviceIdElement.getText().toString();
			// ??????200 OK
			responseAck(evt);

			Device device = storager.queryVideoDevice(deviceId);
			if (device == null) {
				return;
			}

			if (rootElement.getName().equals("Notify")) {	// ??????????????????
				DeviceAlarm deviceAlarm = new DeviceAlarm();
				deviceAlarm.setDeviceId(deviceId);
				deviceAlarm.setAlarmPriority(XmlUtil.getText(rootElement, "AlarmPriority"));
				deviceAlarm.setAlarmMethod(XmlUtil.getText(rootElement, "AlarmMethod"));
				deviceAlarm.setAlarmTime(XmlUtil.getText(rootElement, "AlarmTime"));
				if (XmlUtil.getText(rootElement, "AlarmDescription") == null) {
					deviceAlarm.setAlarmDescription("");
				} else {
					deviceAlarm.setAlarmDescription(XmlUtil.getText(rootElement, "AlarmDescription"));
				}
				if (NumericUtil.isDouble(XmlUtil.getText(rootElement, "Longitude"))) {
					deviceAlarm.setLongitude(Double.parseDouble(XmlUtil.getText(rootElement, "Longitude")));
				} else {
					deviceAlarm.setLongitude(0.00);
				}
				if (NumericUtil.isDouble(XmlUtil.getText(rootElement, "Latitude"))) {
					deviceAlarm.setLatitude(Double.parseDouble(XmlUtil.getText(rootElement, "Latitude")));
				} else {
					deviceAlarm.setLatitude(0.00);
				}
	
				if (!XmlUtil.isEmpty(deviceAlarm.getAlarmMethod())) {
					if ( deviceAlarm.getAlarmMethod().equals("4")) {
						MobilePosition mobilePosition = new MobilePosition();
						mobilePosition.setDeviceId(deviceAlarm.getDeviceId());
						mobilePosition.setTime(deviceAlarm.getAlarmTime());
						mobilePosition.setLongitude(deviceAlarm.getLongitude());
						mobilePosition.setLatitude(deviceAlarm.getLatitude());
						mobilePosition.setReportSource("GPS Alarm");
						BaiduPoint bp = new BaiduPoint();
						bp = GpsUtil.Wgs84ToBd09(String.valueOf(mobilePosition.getLongitude()), String.valueOf(mobilePosition.getLatitude()));
						logger.info("???????????????" + bp.getBdLng() + ", " + bp.getBdLat());
						mobilePosition.setGeodeticSystem("BD-09");
						mobilePosition.setCnLng(bp.getBdLng());
						mobilePosition.setCnLat(bp.getBdLat());
						if (!userSetup.getSavePositionHistory()) {
							storager.clearMobilePositionsByDeviceId(deviceId);
						}
						storager.insertMobilePosition(mobilePosition);
					}
				}
				// TODO: ?????????????????????????????????????????????
	
				if (offLineDetector.isOnline(deviceId)) {
					publisher.deviceAlarmEventPublish(deviceAlarm);
				}
			} else if (rootElement.getName().equals("Response")) {	// ????????????????????????
				JSONObject json = new JSONObject();
				XmlUtil.node2Json(rootElement, json);
				if (logger.isDebugEnabled()) {
					logger.debug(json.toJSONString());
				}
				RequestMessage msg = new RequestMessage();
				msg.setDeviceId(deviceId);
				msg.setType(DeferredResultHolder.CALLBACK_CMD_ALARM);
				msg.setData(json);
				deferredResultHolder.invokeResult(msg);
			}
		} catch (DocumentException | SipException | InvalidArgumentException | ParseException e) {
			// } catch (DocumentException e) {
			e.printStackTrace();
		}
	}

	/***
	 * ??????keepalive?????? ??????
	 * 
	 * @param evt
	 */
	private void processMessageKeepAlive(RequestEvent evt) {
		try {
			Element rootElement = getRootElement(evt);
			String deviceId = XmlUtil.getText(rootElement, "DeviceID");
			// ??????????????????????????? ?????????????????????
			if (storager.exists(deviceId)) {
				// ??????200 OK
				responseAck(evt);
				if (offLineDetector.isOnline(deviceId)) {
					publisher.onlineEventPublish(deviceId, VideoManagerConstants.EVENT_ONLINE_KEEPLIVE);
				} else {
				}
			}
		} catch (ParseException | SipException | InvalidArgumentException | DocumentException e) {
			e.printStackTrace();
		}
	}

	/***
	 * ??????RecordInfo??????????????????Message?????? TODO ????????????????????????180???????????????DeferredResult????????????????????????
	 * 
	 * @param evt
	 */
	private void processMessageRecordInfo(RequestEvent evt) {
		try {
			// ??????200 OK
			responseAck(evt);
			String uuid = UUID.randomUUID().toString().replace("-", "");
			RecordInfo recordInfo = new RecordInfo();
			Element rootElement = getRootElement(evt);
			Element deviceIdElement = rootElement.element("DeviceID");
			String deviceId = deviceIdElement.getText().toString();
			recordInfo.setDeviceId(deviceId);
			recordInfo.setName(XmlUtil.getText(rootElement, "Name"));
			if (XmlUtil.getText(rootElement, "SumNum")== null || XmlUtil.getText(rootElement, "SumNum") =="") {
				recordInfo.setSumNum(0);
			} else {
				recordInfo.setSumNum(Integer.parseInt(XmlUtil.getText(rootElement, "SumNum")));
			}
			String sn = XmlUtil.getText(rootElement, "SN");
			Element recordListElement = rootElement.element("RecordList");
			if (recordListElement == null || recordInfo.getSumNum() == 0) {
				logger.info("???????????????");
				RequestMessage msg = new RequestMessage();
				msg.setDeviceId(deviceId);
				msg.setType(DeferredResultHolder.CALLBACK_CMD_RECORDINFO);
				msg.setData(recordInfo);
				deferredResultHolder.invokeResult(msg);
			} else {
				Iterator<Element> recordListIterator = recordListElement.elementIterator();
				List<RecordItem> recordList = new ArrayList<RecordItem>();
				if (recordListIterator != null) {
					RecordItem record = new RecordItem();
					logger.info("????????????????????????...");
					// ??????DeviceList
					while (recordListIterator.hasNext()) {
						Element itemRecord = recordListIterator.next();
						Element recordElement = itemRecord.element("DeviceID");
						if (recordElement == null) {
							logger.info("????????????????????????...");
							continue;
						}
						record = new RecordItem();
						record.setDeviceId(XmlUtil.getText(itemRecord, "DeviceID"));
						record.setName(XmlUtil.getText(itemRecord, "Name"));
						record.setFilePath(XmlUtil.getText(itemRecord, "FilePath"));
						record.setAddress(XmlUtil.getText(itemRecord, "Address"));
						record.setStartTime(
								DateUtil.ISO8601Toyyyy_MM_dd_HH_mm_ss(XmlUtil.getText(itemRecord, "StartTime")));
						record.setEndTime(
								DateUtil.ISO8601Toyyyy_MM_dd_HH_mm_ss(XmlUtil.getText(itemRecord, "EndTime")));
						record.setSecrecy(itemRecord.element("Secrecy") == null ? 0
								: Integer.parseInt(XmlUtil.getText(itemRecord, "Secrecy")));
						record.setType(XmlUtil.getText(itemRecord, "Type"));
						record.setRecorderId(XmlUtil.getText(itemRecord, "RecorderID"));
						recordList.add(record);
					}
					recordInfo.setRecordList(recordList);
				}

				// ??????????????????????????????????????????????????????????????????????????????????????????????????????
				String cacheKey = CACHE_RECORDINFO_KEY + deviceId + sn;
				redis.set(cacheKey + "_" + uuid, recordList, 90);
				if (!threadNameList.contains(cacheKey)) {
					threadNameList.add(cacheKey);
					CheckForAllRecordsThread chk = new CheckForAllRecordsThread(cacheKey, recordInfo);
					chk.setName(cacheKey);
					chk.setDeferredResultHolder(deferredResultHolder);
					chk.setRedis(redis);
					chk.setLogger(logger);
					chk.start();
					if (logger.isDebugEnabled()) {
						logger.debug("Start Thread " + cacheKey + ".");
					}
				} else {
					if (logger.isDebugEnabled()) {
						logger.debug("Thread " + cacheKey + " already started.");
					}
				}
			
				// ???????????????????????????????????????????????????????????????????????????????????????????????????????????????
				// if (recordInfo.getSumNum() > 0 && recordList.size() > 0 && recordList.size() < recordInfo.getSumNum()) {
				// 	// ??????????????????????????????????????????????????????????????????????????????sn????????????
				// 	String cacheKey = CACHE_RECORDINFO_KEY + deviceId + sn;

				// 	redis.set(cacheKey + "_" + uuid, recordList, 90);
				// 	List<Object> cacheKeys = redis.scan(cacheKey + "_*");
				// 	List<RecordItem> totalRecordList = new ArrayList<RecordItem>();
				// 	for (int i = 0; i < cacheKeys.size(); i++) {
				// 		totalRecordList.addAll((List<RecordItem>) redis.get(cacheKeys.get(i).toString()));
				// 	}
				// 	if (totalRecordList.size() < recordInfo.getSumNum()) {
				// 		logger.info("?????????" + totalRecordList.size() + "?????????????????????" + recordInfo.getSumNum() + "???");
				// 		return;
				// 	}
				// 	logger.info("?????????????????????????????????" + recordInfo.getSumNum() + "???");
				// 	recordInfo.setRecordList(totalRecordList);
				// 	for (int i = 0; i < cacheKeys.size(); i++) {
				// 		redis.del(cacheKeys.get(i).toString());
				// 	}
				// }
				// // ??????????????????, ????????????????????????
				// recordInfo.getRecordList().sort(Comparator.naturalOrder());
			}
			// ?????????????????????????????????1?????????????????????,???????????????recordinfo????????????????????????????????????redis??????
			// 2?????????????????????????????????????????????????????????????????????????????????redis??????
			// 3??????????????????????????????????????????????????????????????????????????????????????????

			// RequestMessage msg = new RequestMessage();
			// msg.setDeviceId(deviceId);
			// msg.setType(DeferredResultHolder.CALLBACK_CMD_RECORDINFO);
			// msg.setData(recordInfo);
			// deferredResultHolder.invokeResult(msg);
			// logger.info("???????????????????????????");
		} catch (DocumentException | SipException | InvalidArgumentException | ParseException e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????MediaStatus????????????
 	 *  
 	 * @param evt
 	 */
	private void processMessageMediaStatus(RequestEvent evt){
		try {
			// ??????200 OK
			responseAck(evt);
			Element rootElement = getRootElement(evt);
			String deviceId = XmlUtil.getText(rootElement, "DeviceID");
			String NotifyType =XmlUtil.getText(rootElement, "NotifyType");
			if (NotifyType.equals("121")){
				logger.info("?????????????????????????????????");
				StreamInfo streamInfo = redisCatchStorage.queryPlaybackByDevice(deviceId, "*");
				if (streamInfo != null) {
					redisCatchStorage.stopPlayback(streamInfo);
					cmder.streamByeCmd(streamInfo.getStreamId());
				}
			}
		} catch (ParseException | SipException | InvalidArgumentException | DocumentException e) {
			e.printStackTrace();
		}
	}


	/***
	 * ??????200 OK
	 * @param evt
	 * @throws SipException
	 * @throws InvalidArgumentException
	 * @throws ParseException
	 */
	private void responseAck(RequestEvent evt) throws SipException, InvalidArgumentException, ParseException {
		Response response = getMessageFactory().createResponse(Response.OK, evt.getRequest());
		getServerTransaction(evt).sendResponse(response);
	}

	private Element getRootElement(RequestEvent evt) throws DocumentException {
		Request request = evt.getRequest();
		SAXReader reader = new SAXReader();
		reader.setEncoding("gbk");
		Document xml = reader.read(new ByteArrayInputStream(request.getRawContent()));
		return xml.getRootElement();
	}

	public void setCmder(SIPCommander cmder) {
		this.cmder = cmder;
	}

	public void setStorager(IVideoManagerStorager storager) {
		this.storager = storager;
	}

	public void setPublisher(EventPublisher publisher) {
		this.publisher = publisher;
	}

	public void setRedis(RedisUtil redis) {
		this.redis = redis;
	}

	public void setDeferredResultHolder(DeferredResultHolder deferredResultHolder) {
		this.deferredResultHolder = deferredResultHolder;
	}

	public void setOffLineDetector(DeviceOffLineDetector offLineDetector) {
		this.offLineDetector = offLineDetector;
	}

	public IRedisCatchStorage getRedisCatchStorage() {
		return redisCatchStorage;
	}

	public void setRedisCatchStorage(IRedisCatchStorage redisCatchStorage) {
		this.redisCatchStorage = redisCatchStorage;
	}
}