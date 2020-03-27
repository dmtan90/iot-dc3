package com.github.pnoker.driver.service.netty;

import cn.hutool.core.util.CharsetUtil;
import com.github.pnoker.common.bean.driver.PointValue;
import com.github.pnoker.common.model.Point;
import com.github.pnoker.common.sdk.bean.AttributeInfo;
import com.github.pnoker.common.sdk.bean.DriverContext;
import com.github.pnoker.common.sdk.service.rabbit.PointValueService;
import com.github.pnoker.common.sdk.util.DriverUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报文处理，需要视具体情况开发
 * 本驱动中使用报文（设备名称[22]+关键字[1]+海拔[4]+速度[8]+液位[8]+方向[4]+锁定[1]+经纬[21]）进行测试使用
 * 4C 69 73 74 65 6E 69 6E 67 56 69 72 74 75 61 6C 44 65 76 69 63 65
 * 62
 * ‭44 C3 E7 5C‬
 * ‭40 46 D5 C2 8F 5C 28 F6‬
 * 00 00 00 00 00 00 00 0C
 * 00 00 00 2D
 * 01
 * 31 33 31 2E 32 33 31 34 35 36 2C 30 32 31 2E 35 36 38 32 31 31
 * <p>
 * 使用 sokit 发送以下报文
 * lg:[4C 69 73 74 65 6E 69 6E 67 56 69 72 74 75 61 6C 44 65 76 69 63 65 62 44 C3 E7 5C 40 46 D5 C2 8F 5C 28 F6 00 00 00 00 00 00 00 0C 00 00 00 2D 01 31 33 31 2E 32 33 31 34 35 36 2C 30 32 31 2E 35 36 38 32 31 31]
 *
 * @author pnoker
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class NettyServerHandler extends ChannelInboundHandlerAdapter {
    private static NettyServerHandler nettyServerHandler;

    @PostConstruct
    public void init() {
        nettyServerHandler = this;
    }

    @Resource
    private PointValueService pointValueService;
    @Resource
    private DriverContext driverContext;

    @Override
    @SneakyThrows
    public void channelActive(ChannelHandlerContext context) {
        log.debug("listener({}) accept clint({})", context.channel().localAddress(), context.channel().remoteAddress());
    }

    @Override
    @SneakyThrows
    public void channelRead(ChannelHandlerContext context, Object msg) {
        ByteBuf byteBuf = (ByteBuf) msg;
        log.info("{}->{}", context.channel().remoteAddress(), ByteBufUtil.hexDump(byteBuf));
        String deviceName = byteBuf.toString(0, 22, CharsetUtil.CHARSET_ISO_8859_1);
        Long deviceId = nettyServerHandler.driverContext.getDeviceIdByName(deviceName);
        String hexKey = ByteBufUtil.hexDump(byteBuf, 22, 1);

        List<PointValue> pointValues = new ArrayList<>();
        Map<Long, Map<String, AttributeInfo>> pointInfoMap = nettyServerHandler.driverContext.getDevicePointInfoMap().get(deviceId);
        for (Long pointId : pointInfoMap.keySet()) {
            Point point = nettyServerHandler.driverContext.getDevicePoint(deviceId, pointId);
            Map<String, AttributeInfo> infoMap = pointInfoMap.get(pointId);
            int start = DriverUtils.value(infoMap.get("start").getType(), infoMap.get("start").getValue());
            int end = DriverUtils.value(infoMap.get("end").getType(), infoMap.get("end").getValue());

            if (infoMap.get("key").getValue().equals(hexKey)) {
                PointValue pointValue = null;
                switch (point.getName()) {
                    case "海拔":
                        float altitude = byteBuf.getFloat(start);
                        pointValue = nettyServerHandler.pointValueService.convertValue(deviceId, pointId, String.valueOf(altitude));
                        break;
                    case "速度":
                        double speed = byteBuf.getDouble(start);
                        pointValue = nettyServerHandler.pointValueService.convertValue(deviceId, pointId, String.valueOf(speed));
                        break;
                    case "液位":
                        long level = byteBuf.getLong(start);
                        pointValue = nettyServerHandler.pointValueService.convertValue(deviceId, pointId, String.valueOf(level));
                        break;
                    case "方向":
                        int direction = byteBuf.getInt(start);
                        pointValue = nettyServerHandler.pointValueService.convertValue(deviceId, pointId, String.valueOf(direction));
                        break;
                    case "锁定":
                        boolean lock = byteBuf.getBoolean(start);
                        pointValue = nettyServerHandler.pointValueService.convertValue(deviceId, pointId, String.valueOf(lock));
                        break;
                    case "经纬":
                        String lalo = byteBuf.toString(start, end, CharsetUtil.CHARSET_ISO_8859_1).trim();
                        pointValue = nettyServerHandler.pointValueService.convertValue(deviceId, pointId, String.valueOf(lalo));
                        break;
                    default:
                        break;
                }
                if (null != pointValue) {
                    pointValues.add(pointValue);
                }
            }
        }
        nettyServerHandler.pointValueService.pointValueSender(pointValues);
    }

    @Override
    @SneakyThrows
    public void exceptionCaught(ChannelHandlerContext context, Throwable throwable) {
        log.debug(throwable.getMessage());
        context.close();
    }

}