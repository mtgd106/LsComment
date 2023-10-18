package com.vkls;

import com.vkls.entity.Shop;
import com.vkls.service.IShopService;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.junit.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.vkls.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class ApplicationTests {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;
    //将所有的店铺按照类型导入到redis中，以便进行搜索功能
    @Test
    void loadShopData(){
        //1.查询到所有的店铺信息
        List<Shop> list=shopService.list();
        //2.把店铺分组，按照typeId分组，typeId相同的放到一个集合中
        Map<Long,List<Shop>> map=list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批写入redis中
        for(Map.Entry<Long,List<Shop>> entry:map.entrySet()){
            //3.1获取类型id
            Long typeId=entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类型的店铺的集合
            List<Shop> value=entry.getValue();

            //将店铺名称和坐标信息放在GeoLocation中，多个GeoLocation形成一个集合
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());

            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            //将locations加入到redis中
            stringRedisTemplate.opsForGeo().add(key, locations);

        }
    }


}
