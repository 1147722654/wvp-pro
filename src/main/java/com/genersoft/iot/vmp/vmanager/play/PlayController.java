package com.genersoft.iot.vmp.vmanager.play;

import com.alibaba.fastjson.JSON;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.MediaServerConfig;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.vmanager.service.IPlayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommander;
import com.genersoft.iot.vmp.storager.IVideoManagerStorager;
import org.springframework.web.context.request.async.DeferredResult;

import javax.sip.message.Response;
import java.util.UUID;

@CrossOrigin
@RestController
@RequestMapping("/api")
public class PlayController {

	private final static Logger logger = LoggerFactory.getLogger(PlayController.class);

	@Autowired
	private SIPCommander cmder;

	@Autowired
	private IVideoManagerStorager storager;

	@Autowired
	private IRedisCatchStorage redisCatchStorage;

	@Autowired
	private ZLMRESTfulUtils zlmresTfulUtils;

	@Autowired
	private DeferredResultHolder resultHolder;

	@Autowired
	private IPlayService playService;

	@GetMapping("/play/{deviceId}/{channelId}")
	public DeferredResult<ResponseEntity<String>> play(@PathVariable String deviceId,
													   @PathVariable String channelId) {


		Device device = storager.queryVideoDevice(deviceId);
		StreamInfo streamInfo = redisCatchStorage.queryPlayByDevice(deviceId, channelId);

		UUID uuid = UUID.randomUUID();
		DeferredResult<ResponseEntity<String>> result = new DeferredResult<ResponseEntity<String>>();

		// ???????????????channelId??????deviceId??????
		resultHolder.put(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid, result);

		if (streamInfo == null) {
			// ??????????????????
			cmder.playStreamCmd(device, channelId, (JSONObject response) -> {
				logger.info("????????????????????? " + response.toJSONString());
				playService.onPublishHandlerForPlay(response, deviceId, channelId, uuid.toString());
			}, event -> {
				RequestMessage msg = new RequestMessage();
				msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
				Response response = event.getResponse();
				msg.setData(String.format("??????????????? ???????????? %s, %s", response.getStatusCode(), response.getReasonPhrase()));
				resultHolder.invokeResult(msg);
			});
		} else {
			String streamId = streamInfo.getStreamId();
			JSONObject rtpInfo = zlmresTfulUtils.getRtpInfo(streamId);
			if (rtpInfo.getBoolean("exist")) {
				RequestMessage msg = new RequestMessage();
				msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
				msg.setData(JSON.toJSONString(streamInfo));
				resultHolder.invokeResult(msg);
			} else {
				redisCatchStorage.stopPlay(streamInfo);
				storager.stopPlay(streamInfo.getDeviceID(), streamInfo.getChannelId());
				cmder.playStreamCmd(device, channelId, (JSONObject response) -> {
					logger.info("????????????????????? " + response.toJSONString());
					playService.onPublishHandlerForPlay(response, deviceId, channelId, uuid.toString());
				}, event -> {
					RequestMessage msg = new RequestMessage();
					msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
					Response response = event.getResponse();
					msg.setData(String.format("??????????????? ???????????? %s, %s", response.getStatusCode(), response.getReasonPhrase()));
					resultHolder.invokeResult(msg);
				});
			}
		}

		// ????????????
		result.onTimeout(()->{
			logger.warn(String.format("?????????????????????deviceId???%s ???channelId???%s", deviceId, channelId));
			// ??????rtpserver
			cmder.closeRTPServer(device, channelId);
			RequestMessage msg = new RequestMessage();
			msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
			msg.setData("Timeout");
			resultHolder.invokeResult(msg);
		});
		return result;
	}

	@PostMapping("/play/{streamId}/stop")
	public DeferredResult<ResponseEntity<String>> playStop(@PathVariable String streamId) {

		logger.debug(String.format("????????????/????????????API?????????streamId???%s", streamId));

		UUID uuid = UUID.randomUUID();
		DeferredResult<ResponseEntity<String>> result = new DeferredResult<ResponseEntity<String>>();

		// ???????????????channelId??????deviceId??????
		resultHolder.put(DeferredResultHolder.CALLBACK_CMD_STOP + uuid, result);

		cmder.streamByeCmd(streamId, event -> {
			StreamInfo streamInfo = redisCatchStorage.queryPlayByStreamId(streamId);
			if (streamInfo == null) {
				RequestMessage msg = new RequestMessage();
				msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
				msg.setData("streamId not found");
				resultHolder.invokeResult(msg);
			}else {
				redisCatchStorage.stopPlay(streamInfo);
				storager.stopPlay(streamInfo.getDeviceID(), streamInfo.getChannelId());
				RequestMessage msg = new RequestMessage();
				msg.setId(DeferredResultHolder.CALLBACK_CMD_STOP + uuid);
				//Response response = event.getResponse();
				msg.setData(String.format("success"));
				resultHolder.invokeResult(msg);
			}
		});

		if (streamId != null) {
			JSONObject json = new JSONObject();
			json.put("streamId", streamId);
			RequestMessage msg = new RequestMessage();
			msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
			msg.setData(json.toString());
			resultHolder.invokeResult(msg);
		} else {
			logger.warn("????????????/????????????API???????????????");
			RequestMessage msg = new RequestMessage();
			msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
			msg.setData("streamId null");
			resultHolder.invokeResult(msg);
		}

		// ????????????
		result.onTimeout(()->{
			logger.warn(String.format("????????????/?????????????????????streamId???%s ", streamId));
			RequestMessage msg = new RequestMessage();
			msg.setId(DeferredResultHolder.CALLBACK_CMD_STOP + uuid);
			msg.setData("Timeout");
			resultHolder.invokeResult(msg);
		});
		return result;
	}

	/**
	 * ?????????h264???????????????ffmpeg ?????????h264 + aac
	 * @param streamId ???ID
	 * @return
	 */
	@PostMapping("/play/{streamId}/convert")
	public ResponseEntity<String> playConvert(@PathVariable String streamId) {
		StreamInfo streamInfo = redisCatchStorage.queryPlayByStreamId(streamId);
		if (streamInfo == null) {
			logger.warn("????????????API???????????????, ?????????????????????!");
			return new ResponseEntity<String>("????????????????????????, ???????????????????????????", HttpStatus.OK);
		}
		JSONObject rtpInfo = zlmresTfulUtils.getRtpInfo(streamId);
		if (!rtpInfo.getBoolean("exist")) {
			logger.warn("????????????API???????????????, ????????????????????????!");
			return new ResponseEntity<String>("????????????????????????????????????, ??????????????????????????????", HttpStatus.OK);
		} else {
			MediaServerConfig mediaInfo = redisCatchStorage.getMediaInfo();
			String dstUrl = String.format("rtmp://%s:%s/convert/%s", "127.0.0.1", mediaInfo.getRtmpPort(),
					streamId );
			String srcUrl = String.format("rtsp://%s:%s/rtp/%s", "127.0.0.1", mediaInfo.getRtspPort(), streamId);
			JSONObject jsonObject = zlmresTfulUtils.addFFmpegSource(srcUrl, dstUrl, "1000000");
			System.out.println(jsonObject);
			JSONObject result = new JSONObject();
			if (jsonObject != null && jsonObject.getInteger("code") == 0) {
				   result.put("code", 0);
				JSONObject data = jsonObject.getJSONObject("data");
				if (data != null) {
				   	result.put("key", data.getString("key"));
					StreamInfo streamInfoResult = new StreamInfo();
					streamInfoResult.setRtmp(dstUrl);
					streamInfoResult.setRtsp(String.format("rtsp://%s:%s/convert/%s", mediaInfo.getWanIp(), mediaInfo.getRtspPort(), streamId));
					streamInfoResult.setStreamId(streamId);
					streamInfoResult.setFlv(String.format("http://%s:%s/convert/%s.flv", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
					streamInfoResult.setWs_flv(String.format("ws://%s:%s/convert/%s.flv", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
					streamInfoResult.setHls(String.format("http://%s:%s/convert/%s/hls.m3u8", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
					streamInfoResult.setWs_hls(String.format("ws://%s:%s/convert/%s/hls.m3u8", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
					streamInfoResult.setFmp4(String.format("http://%s:%s/convert/%s.live.mp4", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
					streamInfoResult.setWs_fmp4(String.format("ws://%s:%s/convert/%s.live.mp4", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
					streamInfoResult.setTs(String.format("http://%s:%s/convert/%s.live.ts", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
					streamInfoResult.setWs_ts(String.format("ws://%s:%s/convert/%s.live.ts", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
					result.put("data", streamInfoResult);
				}
			}else {
				result.put("code", 1);
				result.put("msg", "cover fail");
			}
			return new ResponseEntity<String>( result.toJSONString(), HttpStatus.OK);
		}
	}

	/**
	 * ????????????
	 * @param key
	 * @return
	 */
	@PostMapping("/play/convert/stop/{key}")
	public ResponseEntity<String> playConvertStop(@PathVariable String key) {

		JSONObject jsonObject = zlmresTfulUtils.delFFmpegSource(key);
		System.out.println(jsonObject);
		JSONObject result = new JSONObject();
		if (jsonObject != null && jsonObject.getInteger("code") == 0) {
			result.put("code", 0);
			JSONObject data = jsonObject.getJSONObject("data");
			if (data != null && data.getBoolean("flag")) {
				result.put("code", "0");
				result.put("msg", "success");
			}else {

			}
		}else {
			result.put("code", 1);
			result.put("msg", "delFFmpegSource fail");
		}
		return new ResponseEntity<String>( result.toJSONString(), HttpStatus.OK);
	}
}

