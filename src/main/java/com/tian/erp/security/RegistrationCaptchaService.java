package com.tian.erp.security;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Base64;
import java.security.SecureRandom;
import java.util.Map;
import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** 生成并校验绑定浏览器会话的单次注册图形验证码。 */
@Service
public class RegistrationCaptchaService {
    static final String SESSION_KEY = RegistrationCaptchaService.class.getName() + ".challenge";
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LIFETIME_MS = 300_000;
    private final Clock clock;

    record Challenge(String answer, long expiresAt) {}

    public RegistrationCaptchaService() {
        this(Clock.systemUTC());
    }

    RegistrationCaptchaService(Clock clock) {
        this.clock = clock;
    }

    /** 刷新时替换旧验证码，返回 PNG 数据和有效秒数，不返回答案。 */
    public Map<String, Object> create(HttpSession session) {
        var answer = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            answer.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String image = render(answer.toString());
        synchronized (session) {
            session.setAttribute(SESSION_KEY, new Challenge(answer.toString(), clock.millis() + LIFETIME_MS));
        }
        return Map.of("image", image, "expires_in", LIFETIME_MS / 1000);
    }

    /** 无论验证成功或失败都消费本次验证码，避免并发注册复用同一答案。 */
    public void verify(HttpSession session, Object value) {
        if (session == null) {
            throw invalid();
        }
        synchronized (session) {
            var stored = session.getAttribute(SESSION_KEY);
            session.removeAttribute(SESSION_KEY);
            if (!(stored instanceof Challenge challenge) || clock.millis() >= challenge.expiresAt()
                    || !(value instanceof String answer) || !challenge.answer().equalsIgnoreCase(answer.trim())) {
                throw invalid();
            }
        }
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(BAD_REQUEST, "图形验证码错误或已失效，请刷新后重试");
    }

    private String render(String answer) {
        var image = new BufferedImage(156, 52, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(239, 247, 243));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < 16; i++) {
                graphics.setColor(new Color(130 + RANDOM.nextInt(70), 170 + RANDOM.nextInt(60), 160 + RANDOM.nextInt(65)));
                graphics.drawLine(RANDOM.nextInt(156), RANDOM.nextInt(52), RANDOM.nextInt(156), RANDOM.nextInt(52));
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            for (int i = 0; i < answer.length(); i++) {
                graphics.setColor(new Color(15 + RANDOM.nextInt(60), 65 + RANDOM.nextInt(50), 55 + RANDOM.nextInt(50)));
                graphics.drawString(String.valueOf(answer.charAt(i)), 15 + i * 34, 35 + RANDOM.nextInt(7));
            }
        } finally {
            graphics.dispose();
        }
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("无法生成验证码图片", ex);
        }
    }
}