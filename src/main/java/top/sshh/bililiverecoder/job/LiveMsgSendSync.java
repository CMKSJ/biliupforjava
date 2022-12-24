package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LiveMsgSendSync {

    @Autowired
    private BiliUserRepository userRepository;

    @Autowired
    private LiveMsgRepository msgRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private LiveMsgService liveMsgService;

    private static final Lock lock = new ReentrantLock();

    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void sndMsgProcess() {
        log.info("发送弹幕定时任务开始");
        long startTime = System.currentTimeMillis();
        List<RecordHistory> historyList = historyRepository.findByPublishIsTrueAndCode(0);
        if (CollectionUtils.isEmpty(historyList)) {
            return;
        }
        List<RecordHistoryPart> partList = new ArrayList<>();
        for (RecordHistory history : historyList) {
            List<RecordHistoryPart> parts = partRepository.findByHistoryIdAndCidIsNotNull(history.getId());
            partList.addAll(parts);
        }
        if (CollectionUtils.isEmpty(partList)) {
            return;
        }
        List<LiveMsg> msgAllList = new ArrayList<>();
        for (RecordHistoryPart part : partList) {
            List<LiveMsg> msgList = msgRepository.findByPartIdAndCode(part.getId(), -1);
            if (CollectionUtils.isEmpty(msgList)) {
                continue;
            }
            msgAllList.addAll(msgList);

        }
        if (msgAllList.isEmpty()) {
            return;
        }

        List<BiliBiliUser> allUser = userRepository.findByLoginIsTrueAndEnableIsTrue();
        if (CollectionUtils.isEmpty(allUser)) {
            return;
        }
        try {
            boolean tryLock = lock.tryLock();
            if (!tryLock) {
                log.error("弹幕发获取锁失败！！！！");
                return;
            }
            //高优先级弹幕，如sc,舰长，只能由视频发布账号发送
            List<LiveMsg> highLevelMsg = msgAllList.stream().filter(liveMsg -> liveMsg.getPool() == 1).sorted((m1, m2) -> (int) (m1.getSendTime() - m2.getSendTime())).collect(Collectors.toList());
            log.info("即将开始高级弹幕发送操作，剩余待发送弹幕{}条。", highLevelMsg.size());
            for (LiveMsg msg : highLevelMsg) {
                Long partId = msg.getPartId();
                Optional<RecordHistoryPart> partOptional = partRepository.findById(partId);
                if (partOptional.isPresent()) {
                    RecordHistoryPart part = partOptional.get();
                    String roomId = part.getRoomId();
                    RecordRoom room = roomRepository.findByRoomId(roomId);
                    if (room != null) {
                        Long uploadUserId = room.getUploadUserId();
                        Optional<BiliBiliUser> userOptional = userRepository.findById(uploadUserId);
                        if (userOptional.isPresent()) {
                            BiliBiliUser user = userOptional.get();
                            if (!(user.isLogin() && user.isEnable())) {
                                continue;
                            }
                            int code = liveMsgService.sendMsg(user, msg);
                            if (code != 0) {
                                log.error("{}用户，发送失败，错误代码{}，弹幕内容为。==>{}", user.getUname(), code, msg.getContext());
                            }
                            try {
                                if (code == 36703) {
                                    Thread.sleep(120 * 1000L);
                                } else if (code == 0) {
                                    Thread.sleep(25 * 1000L);
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            continue;
                        }
                    }
                }
                msg.setCode(0);
                msgRepository.save(msg);
            }
            msgAllList = msgAllList.stream().filter(liveMsg -> liveMsg.getPool() == 0).sorted((m1, m2) -> (int) (m1.getSendTime() - m2.getSendTime())).collect(Collectors.toList());
            BlockingQueue<LiveMsg> msgQueue = new ArrayBlockingQueue<>(msgAllList.size());
            msgQueue.addAll(msgAllList);
            AtomicInteger count = new AtomicInteger(0);
            log.info("即将开始普通弹幕发送操作，剩余待发送弹幕{}条。", msgQueue.size());
            allUser.stream().parallel().forEach(user -> {
                while (msgQueue.size() > 0) {
                    if (System.currentTimeMillis() - startTime > 2 * 3600 * 1000) {
                        log.error("弹幕发送超时，重新启动");
                        return;
                    }
                    LiveMsg msg = null;
                    try {
                        msg = msgQueue.poll(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (msg == null) {
                        return;
                    }
                    count.incrementAndGet();
                    user = userRepository.findByUid(user.getUid());
                    if (!(user.isLogin() && user.isEnable())) {
                        log.error("弹幕发送：有用户状态为未登录或未启用状态，退出任务。");
                        return;
                    }
                    int code = liveMsgService.sendMsg(user, msg);
                    if (code != 0 && code != 36703 && code != 36714) {
                        log.error("{}用户，发送失败，错误代码{}，一共发送{}条弹幕。", user.getUname(), code, count.get());
                        user.setEnable(false);
                        user = userRepository.save(user);
                        return;
                    } else if (code == 36703) {
                        log.error("{}用户，发送失败，错误代码{}，一共发送{}条弹幕。", user.getUname(), code, count.get());
                    }
                    try {
                        if (code == 36703) {
                            Thread.sleep(120 * 1000L);
                        } else if (code == 0) {
                            Thread.sleep(25 * 1000L);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });
        } finally {
            lock.unlock();
        }

    }
}
