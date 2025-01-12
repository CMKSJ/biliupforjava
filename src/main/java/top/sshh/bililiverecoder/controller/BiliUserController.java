package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.data.BiliSessionDto;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.util.BiliApi;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/biliUser")
public class BiliUserController {

    @Autowired
    BiliUserRepository biliUserRepository;

    private final Map<String, Future<String>> futureMap = new HashMap<>();
    ExecutorService service = Executors.newFixedThreadPool(10);

    @GetMapping("/login")
    public String loginUser() throws Exception {

        BiliApi.BiliResponseDto<BiliApi.GenerateQRDto> s = BiliApi.generateQRUrlTV();
        if (s.getCode() != 0) {
            throw new RuntimeException("生成二维码异常，请检查日志");
        }
        BitMatrix bm = new QRCodeWriter().encode(s.getData().getUrl(),
                BarcodeFormat.QR_CODE, 256, 256);
        BufferedImage bi = MatrixToImageWriter.toBufferedImage(bm);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpg", stream);
        byte[] bytes = Base64.encodeBase64(stream.toByteArray());
        // 偷懒直接new一个Thread
        // new thread to check login status
        Callable<String> callable = () -> {
            try {
                Thread.sleep(5000);
                String loginResp = "";
                for (int i = 0; i < 60; i++) {
                    loginResp = BiliApi.loginOnTV(s.getData().getAuth_code());
                    Integer code = JsonPath.read(loginResp, "code");
                    if (code == 0) {
                        BiliSessionDto dto = JSON.parseObject(loginResp).getObject("data", BiliSessionDto.class);
                        BiliBiliUser biliUser = biliUserRepository.findByUid(dto.getMid());
                        if (biliUser == null) {
                            biliUser = new BiliBiliUser();
                        }
                        JSONArray cookies = JSON.parseArray(JsonPath.read(loginResp, "data.cookie_info.cookies").toString());
                        StringBuilder cookieString = new StringBuilder();
                        for (Object object : cookies) {
                            JSONObject cookie = (JSONObject) object;
                            cookieString.append(cookie.get("name").toString());
                            cookieString.append(":");
                            cookieString.append(cookie.get("value").toString());
                            cookieString.append("; ");
                        }
                        biliUser.setCookies(cookieString.toString());
                        biliUser.setUid(dto.getMid());
                        biliUser.setAccessToken(dto.getAccessToken());
                        biliUser.setRefreshToken(dto.getRefreshToken());
                        biliUser.setLogin(true);
                        biliUser.setUpdateTime(LocalDateTime.now());
                        String userInfo = BiliApi.appMyInfo(biliUser);
                        biliUser.setUname(JsonPath.read(userInfo, "data.uname"));
                        log.info("{} 登录成功!!!", biliUser.getUname());
                        biliUserRepository.save(biliUser);
                        return "登录成功";
                    } else if (code == 86038) {
                        log.info("扫码超时");
                        return JsonPath.read(loginResp, "message");
                    }
                    Thread.sleep(5000);

                }
                return "登录失败，" + JsonPath.read(loginResp, "message");
            } catch (InterruptedException e) {
                return "登录失败";
            }
        };
        Future<String> submit = service.submit(callable);
        String imagesBase64 = new String(bytes);
        futureMap.put(imagesBase64.substring(imagesBase64.length() - 100), submit);
        return imagesBase64;
    }

    @GetMapping("loginReturn")
    public Map<String, String> loginReturn(@RequestParam String key) throws ExecutionException, InterruptedException, TimeoutException {
        Future<String> stringFuture = futureMap.get(key);
        Map<String, String> result = new HashMap<>();
        if (stringFuture == null) {
            result.put("type", "warning");
            result.put("msg", "登录失败");
            return result;
        } else {
            futureMap.remove(key);
            result.put("type", "warning");
            result.put("msg", stringFuture.get(5, TimeUnit.MINUTES));
            return result;
        }
    }

    @GetMapping("/list")
    public List<BiliBiliUser> listBillUser() {
        List<BiliBiliUser> list = new ArrayList<>();
        for (BiliBiliUser biliBiliUser : biliUserRepository.findAll()) {
            biliBiliUser.setAccessToken(null);
            biliBiliUser.setRefreshToken(null);
            list.add(biliBiliUser);
        }
        return list;
    }

    @PostMapping("/update")
    public boolean updateBillUser(@RequestBody BiliBiliUser user) {
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(user.getId());
        if (userOptional.isPresent()) {
            BiliBiliUser dbUser = userOptional.get();
            dbUser.setEnable(user.isEnable());
            dbUser.setUpdateTime(LocalDateTime.now());
            biliUserRepository.save(dbUser);
        }
        return false;
    }

    @GetMapping("/delete/{id}")
    public Map<String, String> delete(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入用户id");
            return result;
        }

        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(id);
        if (userOptional.isPresent()) {
            biliUserRepository.delete(userOptional.get());
            result.put("type", "success");
            result.put("msg", "用户删除成功");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "用户不存在");
            return result;
        }
    }
}
