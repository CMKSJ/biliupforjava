package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.jayway.jsonpath.JsonPath;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.data.SingleVideoDto;
import top.sshh.bililiverecoder.entity.data.VideoUploadDto;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.user.UserMy;
import top.sshh.bililiverecoder.util.bili.user.UserMyRootBean;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class RecordBiliPublishService {

    @Value("${record.wx-push-token}")
    private String wxToken;

    private static final String WX_MSG_FORMAT= """
            投稿结果: %s
            收到主播%s投稿事件
            房间名: %s
            时间: %s
            原因: %s
            """;
    @Autowired
    private BiliUserRepository biliUserRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private UploadServiceFactory uploadServiceFactory;

    @Async
    public void asyncPublishRecordHistory(RecordHistory history) {
        this.publishRecordHistory(history);
    }

    @Async
    public void asyncRepublishRecordHistory(RecordHistory history) {

        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        String wxuid = room.getWxuid();
        String pushMsgTags = room.getPushMsgTags();
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
        if (!userOptional.isPresent()) {
            log.error("视频发布事件，用户不存在，无法发布 ==>{}", JSON.toJSONString(room));
        }
        BiliBiliUser biliBiliUser = userOptional.get();
        if (!biliBiliUser.isLogin()) {
            log.error("视频发布事件，用户登录状态失效，无法发布，请重新登录 ==>{}", JSON.toJSONString(room));
        }

        // 发布任务入队列
        TaskUtil.publishTask.put(history.getId(), Thread.currentThread());
        try {
            List<RecordHistoryPart> uploadParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart uploadPart : uploadParts) {
                // 已经发布成功的不需要在上传
                if (uploadPart.getCid() != null && uploadPart.getCid() > 0) {
                    continue;
                } else {
                    uploadPart.setUpload(false);
                    uploadPart = partRepository.save(uploadPart);
                    String filePath = uploadPart.getFilePath().intern();
                    File file = new File(filePath);
                    if (file.exists()) {
                        synchronized (filePath) {
                            log.error("视频重新发布流程获取part上传锁成功，即将再次检查是否已上传完成");
                            //再次检查是否上传完成
                            Optional<RecordHistoryPart> partOptional = partRepository.findById(uploadPart.getId());
                            if (partOptional.isPresent()) {
                                RecordHistoryPart part = partOptional.get();
                                if (!part.isUpload()) {
                                    log.error("视频发布流程获取part上传锁成功，检查到未上传完成");
                                    uploadServiceFactory.getUploadService(room.getLine()).upload(uploadPart);
                                }
                            }

                        }
                    }
                }
            }
            userOptional = biliUserRepository.findById(room.getUploadUserId());
            if (!userOptional.isPresent()) {
                log.error("视频发布事件，用户不存在，无法发布 ==>{}", JSON.toJSONString(room));
            }
            biliBiliUser = userOptional.get();
            Map<String, Object> map = new HashMap<>();
            LocalDateTime startTime = history.getStartTime();
            map.put("date", startTime);

            String uname = room.getUname();
            map.put("${uname}", uname);
            String title = StringUtils.isNotBlank(history.getTitle()) ? history.getTitle() : "直播录像";
            map.put("${title}", title);
            map.put("${roomId}", room.getRoomId());
            map.put("${areaName}", "");
            List<SingleVideoDto> dtos = new ArrayList<>();
            for (int i = 0; i < uploadParts.size(); i++) {
                RecordHistoryPart uploadPart = uploadParts.get(i);
                SingleVideoDto dto = new SingleVideoDto();
                map.put("date", uploadPart.getStartTime());
                map.put("${index}", Integer.valueOf(i + 1));
                map.put("${areaName}", uploadPart.getAreaName());
                dto.setTitle(this.template(room.getPartTitleTemplate(), map));
                //同步标题
                uploadPart.setTitle(this.template(room.getPartTitleTemplate(), map));
                uploadPart = partRepository.save(uploadPart);
                dto.setDesc("");
                dto.setFilename(uploadPart.getFileName());
                if (uploadPart.getCid() != null && uploadPart.getCid() > 0) {
                    dto.setCid(uploadPart.getCid());
                }
                dtos.add(dto);
            }
            VideoUploadDto videoUploadDto = new VideoUploadDto();

            map.put("date", startTime);
            videoUploadDto.setTid(room.getTid());
            videoUploadDto.setCover(history.getCoverUrl());
            videoUploadDto.setCopyright(room.getCopyright());
            videoUploadDto.setTitle(this.template(room.getTitleTemplate(), map));
            videoUploadDto.setSource(this.template(videoUploadDto.getSource(), map));
            videoUploadDto.setDesc(this.template(room.getDescTemplate(), map));
            videoUploadDto.setDynamic(this.template(room.getDescTemplate(), map));
            videoUploadDto.setVideos(dtos);
            videoUploadDto.setTag(room.getTags());
            videoUploadDto.setAid(Integer.valueOf(history.getAvId()));
            String republishRes = BiliApi.editPublish(biliBiliUser.getAccessToken(), videoUploadDto);
            log.info("重新投稿={}", republishRes);

            // 发布任务出队列
            TaskUtil.publishTask.remove(history.getId());
            Integer code = JsonPath.read(republishRes, "code");
            if (code == 0) {
                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                    Message message = new Message();
                    message.setAppToken(wxToken);
                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                    message.setContent(WX_MSG_FORMAT.formatted("重新投稿成功", room.getUname(), room.getTitle(),
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), ""));
                    message.setUid(wxuid);
                    WxPusher.send(message);
                }
            } else {
                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                    Message message = new Message();
                    message.setAppToken(wxToken);
                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                    message.setContent(WX_MSG_FORMAT.formatted("重新投稿失败", room.getUname(), room.getTitle(),
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                            JsonPath.read(republishRes, "message")));
                    message.setUid(wxuid);
                    WxPusher.send(message);
                }
            }
        } finally {
            TaskUtil.publishTask.remove(history.getId());
        }


    }

    public boolean publishRecordHistory(RecordHistory history) {
        if (history.isPublish()) {
            log.error("视频状态为已发布，退出==>{}", JSON.toJSONString(history));
            return false;
        }
        Thread publishThread = TaskUtil.publishTask.get(history.getId());
        if (publishThread != null) {
            //正在发布，直接退出
            log.error("视频正在发布，退出==>{}", JSON.toJSONString(history));
            return false;
        }
        publishThread = TaskUtil.publishTask.get(history.getId());
        if (publishThread != null) {
            //正在发布，直接退出
            log.error("视频正在发布，直接退出==>{}", JSON.toJSONString(history));
            return false;
        }
        // 发布任务入队列
        TaskUtil.publishTask.put(history.getId(), Thread.currentThread());
        try {

            RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
            String wxuid = room.getWxuid();
            String pushMsgTags = room.getPushMsgTags();
            log.info("发布视频事件开始：{}", room.getUname());

            if (room.getTid() == null) {
                //没有设置分区，直接取消上传
                TaskUtil.publishTask.remove(history.getId());
                log.error("视频没有设置分区，退出==>{}", JSON.toJSONString(history));
                return false;
            }
            List<RecordHistoryPart> uploadParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            if (uploadParts.size() == 0) {
                log.info("发布视频事件分p不能为空，删除分p：{}", JSON.toJSONString(history));
                historyRepository.delete(history);
                TaskUtil.publishTask.remove(history.getId());
                return false;
            }
            LocalDateTime now = LocalDateTime.now();
            for (RecordHistoryPart uploadPart : uploadParts) {
                Optional<RecordHistoryPart> flsuhPartOptional = partRepository.findById(uploadPart.getId());
                uploadPart = flsuhPartOptional.get();
                String filePath = uploadPart.getFilePath().intern();
                File file = new File(filePath);
                //已经上传完成就跳过
                if (uploadPart.isUpload()) {
                    continue;
                }
                if (file.exists()) {
                    if (uploadPart.isRecording() && file.lastModified() > System.currentTimeMillis() - (10 * 60 * 1000)) {
                        log.error("发布视频事件错误，还有分p还在录制中==>{}", JSON.toJSONString(uploadPart));
                        TaskUtil.publishTask.remove(history.getId());
                        return false;
                    } else {
                        uploadPart.setRecording(false);
                        if (uploadPart.getFileSize() == 0 || uploadPart.getDuration() == 0) {
                            uploadPart.setFileSize(file.length());
                            uploadPart.setDuration((float) file.length() / 1024 / 1024);
                        }
                        uploadPart = partRepository.save(uploadPart);
                        if (uploadPart.getEndTime().isAfter(now.plusMinutes(11L))) {
                            log.error("发布视频事件错误，有分p结束时间在十分钟以内==>{}", JSON.toJSONString(uploadPart));
                            TaskUtil.publishTask.remove(history.getId());
                            return false;
                        }
                        if (uploadPart.getFileSize() < 1024 * 1024 * room.getFileSizeLimit()) {
                            log.error("文件大小小于设置的忽略大小，自动删除。");
                            partRepository.delete(uploadPart);
                            continue;
                        }
                        if (uploadPart.getDuration() < room.getDurationLimit()) {
                            log.error("文件时长小于设置的忽略时间，自动删除。");
                            partRepository.delete(uploadPart);
                            continue;
                        }
                    }
                }
                Thread thread = TaskUtil.partUploadTask.get(uploadPart.getId());
                if (thread != null && thread != Thread.currentThread()) {
                    //等待线程上传完成
                    log.info("partId={},{} ===>正在上传 ，等待上传完成在发布,即将等待获取锁", uploadPart.getId(), filePath);
                    synchronized (filePath) {
                        TaskUtil.partUploadTask.remove(uploadPart.getId());
                        log.error("视频发布流程获取part上传锁成功，即将再次检查是否已上传完成");
                        //再次检查是否上传完成
                        Optional<RecordHistoryPart> partOptional = partRepository.findById(uploadPart.getId());
                        if (partOptional.isPresent()) {
                            RecordHistoryPart part = partOptional.get();
                            if (!part.isUpload()) {
                                log.error("视频发布流程获取part上传锁成功，检查到未上传完成");
                                uploadServiceFactory.getUploadService(room.getLine()).upload(uploadPart);
                            }
                        }

                    }
                } else {
                    log.error("视频发布流程,检查到未上传完成,开始上传！");
                    uploadServiceFactory.getUploadService(room.getLine()).upload(uploadPart);
                }

            }
            int preSize = uploadParts.size();
            //重新加载上传列表
            uploadParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            if (preSize != uploadParts.size()) {
                log.error("发布视频事件错误，分p数量发生变动==>{}", JSON.toJSONString(history));
                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                    Message message = new Message();
                    message.setAppToken(wxToken);
                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                    message.setContent(WX_MSG_FORMAT.formatted("投稿失败", room.getUname(), room.getTitle(),
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                            "分p数量发生变动"));
                    message.setUid(wxuid);
                    WxPusher.send(message);
                }
                return false;
            }
            long count = uploadParts.stream().filter(RecordHistoryPart::isUpload).count();
            if (count != uploadParts.size()) {
                //没有全部上传完成返回失败
                TaskUtil.publishTask.remove(history.getId());
                return false;
            }
            if (room.isUpload()) {
                if (room.getUploadUserId() == null) {
                    log.info("视频发布事件，没有设置上传用户，无法发布 ==>{}", JSON.toJSONString(room));
                    TaskUtil.publishTask.remove(history.getId());
                    return false;
                } else {
                    Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
                    if (!userOptional.isPresent()) {
                        log.error("视频发布事件，用户不存在，无法发布 ==>{}", JSON.toJSONString(room));
                        TaskUtil.publishTask.remove(history.getId());
                        return false;
                    }
                    BiliBiliUser biliBiliUser = userOptional.get();
                    if (!biliBiliUser.isLogin()) {
                        log.error("视频发布事件，用户登录状态失效，无法发布，请重新登录 ==>{}", JSON.toJSONString(room));
                        TaskUtil.publishTask.remove(history.getId());
                        return false;
                    }
                    // 检查是否已经过期，调用用户信息接口
                    WebCookie webCookie = Cookie.parse(biliBiliUser.getCookies());
                    UserMy userMy = new UserMy(webCookie);
                    UserMyRootBean myInfo = userMy.getPojo();
                    if (myInfo.getCode() == -101) {
                        biliBiliUser.setLogin(false);
                        biliBiliUser = biliUserRepository.save(biliBiliUser);
                        TaskUtil.publishTask.remove(history.getId());
                        if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                            Message message = new Message();
                            message.setAppToken(wxToken);
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            message.setContent(WX_MSG_FORMAT.formatted("投稿失败", room.getUname(), room.getTitle(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                    biliBiliUser.getUname() + "登录已过期，请重新登录"));
                            message.setUid(wxuid);
                            WxPusher.send(message);
                        }
                        throw new RuntimeException("{}登录已过期，请重新登录! " + biliBiliUser.getUname());
                    }


                    Map<String, Object> map = new HashMap<>();
                    LocalDateTime startTime = history.getStartTime();
                    map.put("date", startTime);

                    String uname = room.getUname();
                    map.put("${uname}", uname);
                    String title = StringUtils.isNotBlank(history.getTitle()) ? history.getTitle() : "直播录像";
                    map.put("${title}", title);
                    map.put("${roomId}", room.getRoomId());
                    map.put("${areaName}", "");
                    List<SingleVideoDto> dtos = new ArrayList<>();
                    for (int i = 0; i < uploadParts.size(); i++) {
                        RecordHistoryPart uploadPart = uploadParts.get(i);
                        SingleVideoDto dto = new SingleVideoDto();
                        map.put("date", uploadPart.getStartTime());
                        map.put("${index}", i + 1);
                        map.put("${areaName}", uploadPart.getAreaName());
                        dto.setTitle(this.template(room.getPartTitleTemplate(), map));
                        //同步标题
                        uploadPart.setTitle(this.template(room.getPartTitleTemplate(), map));
                        uploadPart = partRepository.save(uploadPart);
                        dto.setDesc("");
                        dto.setFilename(uploadPart.getFileName());
                        dtos.add(dto);
                    }
                    String coverUrl = room.getCoverUrl();
                    if ("live".equals(coverUrl)) {
                        try {
                            String filePath = uploadParts.get(uploadParts.size()-1).getFilePath().replaceAll(".flv", ".cover.jpg");
                            File cover = new File(filePath);
                            if (!cover.exists()) {
                                cover = new File(filePath.replaceAll(".jpg", ".png"));
                            }
                            byte[] bytes = new byte[(int) cover.length()];
                            FileInputStream inputStream = new FileInputStream(cover);
                            inputStream.read(bytes);
                            inputStream.close();
                            String uploadCoverResponse = BiliApi.uploadCover(biliBiliUser, cover.getName(), bytes);
                            coverUrl = JsonPath.read(uploadCoverResponse, "data.url");
                        } catch (Exception e) {
                            log.error("{}==>使用直播封面失败", room.getUname(), e);
                            coverUrl = "";
                        }
                    }
                    VideoUploadDto videoUploadDto = new VideoUploadDto();

                    map.put("date", startTime);
                    videoUploadDto.setTid(room.getTid());
                    videoUploadDto.setCover(coverUrl);
                    videoUploadDto.setCopyright(room.getCopyright());
                    videoUploadDto.setTitle(this.template(room.getTitleTemplate(), map));
                    videoUploadDto.setSource(this.template(videoUploadDto.getSource(), map));
                    videoUploadDto.setDesc(this.template(room.getDescTemplate(), map));
                    videoUploadDto.setDynamic(this.template(room.getDescTemplate(), map));
                    videoUploadDto.setVideos(dtos);
                    videoUploadDto.setTag(this.template(room.getTags(), map));
                    String uploadRes = null;
                    try {
                        uploadRes = BiliApi.publish(biliBiliUser.getAccessToken(), videoUploadDto);
                        log.info("uploadRes==>{}", uploadRes);
                        String bvid = JSON.parseObject(uploadRes).getJSONObject("data").getString("bvid");
                        String aid = JSON.parseObject(uploadRes).getJSONObject("data").getString("aid");
                        history.setBvId(bvid);
                        history.setAvId(aid);
                        history.setPublish(true);
                        history = historyRepository.save(history);
                        log.info("发布={}=视频成功 == > {}", room.getUname(), JSON.toJSONString(history));
                        if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                            Message message = new Message();
                            message.setAppToken(wxToken);
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            message.setContent(WX_MSG_FORMAT.formatted("投稿成功", room.getUname(), room.getTitle(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), "bvid=>"+bvid));
                            message.setUid(wxuid);
                            WxPusher.send(message);
                        }
                    } catch (Exception e) {
                        history.setUploadRetryCount(history.getUploadRetryCount() + 1);
                        history = historyRepository.save(history);
                        log.info("发布={}=视频失败 == > {}", room.getUname(), JSON.toJSONString(history), e);
                        if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                            Message message = new Message();
                            message.setAppToken(wxToken);
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            message.setContent(WX_MSG_FORMAT.formatted("投稿失败", room.getUname(), room.getTitle(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), uploadRes != null ? uploadRes : e.getMessage()));
                            message.setUid(wxuid);
                            WxPusher.send(message);
                        }
                    } finally {
                        TaskUtil.publishTask.remove(history.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("投稿发生异常：", e);
        } finally {
            TaskUtil.publishTask.remove(history.getId());
        }
        return true;
    }

    private String template(String template, Map<String, Object> map) {
        template = template.replace("${uname}", map.get("${uname}").toString())
                .replace("${title}", map.get("${title}").toString())
                .replace("${index}", map.get("${index}").toString())
                .replace("${areaName}", map.get("${areaName}").toString())
                .replace("${roomId}", map.get("${roomId}").toString());
        if (template.contains("${")) {
            LocalDateTime localDateTime = (LocalDateTime) map.get("date");
            String date = template.substring(template.indexOf("${"), template.indexOf("}") + 1);
            String format = localDateTime.format(DateTimeFormatter.ofPattern(date.substring(2, date.length() - 1)));
            template = template.replace(date, format);
        }
        return template;
    }
}
