import cn.zmvision.ccm.smserver.service.DataService;
import org.junit.jupiter.api.Test;
import org.toehold.ToeholdServerImp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class test1 {

    private byte[] buildImagePayload(String longAddr, long timestampMs, byte[] picData) {
        byte[] devBytes = to8ByteDeviceAddr(longAddr);
        ByteBuffer buf = ByteBuffer.allocate(8 + 8 + (picData != null ? picData.length : 0));
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(devBytes);
        buf.putLong(timestampMs);  // 大端序写入
        if (picData != null) buf.put(picData);
        return buf.array();
    }

    private byte[] to8ByteDeviceAddr(String addr) {
        try {
            // 移除所有非十六进制字符
            String hex = addr.replaceAll("[^0-9A-Fa-f]", "");

            // 确保正好16个十六进制字符（8字节）
            if (hex.length() < 16) {
                // 左侧补0
                hex = String.format("%16s", hex).replace(' ', '0');
            } else if (hex.length() > 16) {
                // 截断到16字符
                hex = hex.substring(0, 16);
            }

            // 验证格式
            if (hex.matches("[0-9A-Fa-f]{16}")) {
                byte[] out = new byte[8];
                for (int i = 0; i < 8; i++) {
                    String byteStr = hex.substring(i * 2, i * 2 + 2);
                    out[i] = (byte) Integer.parseInt(byteStr, 16);
                }
                return out;
            }
        } catch (Exception e) {
            System.err.println("设备地址转换失败: " + addr + ", 错误: " + e.getMessage());
        }

        // 失败时返回8字节0
        return new byte[8];
    }

    @Test
    public void testBuildImagePayload() {
        String longAddr = "DFA1202509150001";
        long timestampMs = 1730700000000L; // 举例
        byte[] payload = buildImagePayload(longAddr, timestampMs, null);

        // 验证长度
        assertEquals(16, payload.length, "payload length should be 16");

        // 验证前8字节（设备地址）
        byte[] addrBytes = Arrays.copyOfRange(payload, 0, 8);
        String addrHex = bytesToHex(addrBytes);
        assertEquals("DFA1202509150001", addrHex, "device address hex mismatch");

        // 验证时间戳部分（大端long）
        byte[] tsBytes = Arrays.copyOfRange(payload, 8, 16);
        ByteBuffer tsBuf = ByteBuffer.wrap(tsBytes).order(ByteOrder.BIG_ENDIAN);
        long tsParsed = tsBuf.getLong();
        assertEquals(timestampMs, tsParsed, "timestamp mismatch");

        System.out.println("Payload HEX = " + bytesToHex(payload));
        System.out.println("DeviceAddr HEX = " + addrHex);
        System.out.println("Timestamp = " + tsParsed);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

}
