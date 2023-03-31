package com.gzh.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gzh.entity.*;
import com.gzh.entity.Vo.YogiyoAccountManagementVo;
import com.gzh.mapper.ThirdPartyShopInfoMapper;
import com.gzh.service.*;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gzh.utils.*;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 * 第三方店铺信息 服务实现类
 * </p>
 *
 * @author 高智慧
 * @since 2023-03-10 10:27:03
 */
@Service
public class ThirdPartyShopInfoServiceImpl extends ServiceImpl<ThirdPartyShopInfoMapper, ThirdPartyShopInfo> implements ThirdPartyShopInfoService {
    private static final Logger Log = LoggerFactory.getLogger(ThirdPartyShopInfoServiceImpl.class);
    @Resource
    ThirdBrandShopInfoService brandShopInfoService;
    @Resource
    ShippingAddressService shippingAddressService;
    @Resource
    YogiyoAccountManagementService yogiyoAccountManagementService;
    @Resource
    RedisUtil redisUtil;
    @Resource
    YogiyoBankCardService yogiyoBankCardService;
    @Resource
    ThirdPartyPayInfoService thirdPartyPayInfoService;
    @Override
    public JSONArray getShopCategories(String lat, String lng) {
        Map<String, String> param = new HashMap<>();
        param.put("lat",lat);
        param.put("lng",lng);
        String res = HttpClientUtil.specialGet(Constant.HOME_CATEGORIES, param);
        JSONArray categories = new JSONArray();
        System.out.println(res);
        if (StringUtils.isNotEmpty(res)) {
            JSONObject resJson=JSONObject.parseObject(res);
            return resJson.getJSONArray("home_categories");
        }
        return categories;
    }

    @Override
    public JSONObject findThirdPartyShopInfo(String category, String search, String lat, String lng, String items, String page, String orderType) {
        if (StringUtils.isEmpty(category)) {
            return null;
        }
        Map<String, String> param = new HashMap<>();
        if (StringUtils.isNotEmpty(category)) {
            param.put("category", category);
        }
        int i1 = Integer.parseInt(items);
        int i2 = i1 * 3;
        param.put("lat", lat);
        param.put("lng", lng);
        param.put("items", String.valueOf(i2));
        param.put("page", page);
        param.put("payment", "all");
        param.put("max_mov", "");
        param.put("max_delivery_fee", "");
        param.put("use_hotdeal_v2", "true");
        param.put("type", "all");
        param.put("cesco", "");
        param.put("own_delivery_only", "false");
        if (StringUtils.isEmpty(orderType)) {
            param.put("order", "rank");
        }else{
            param.put("order", orderType);
        }
        String res;
        //判断是否要根据店铺查询
        if (StringUtils.isNotEmpty(search)) {
            param.put("search", search);
            res = HttpClientUtil.doYOGIYOGet(Constant.RESTAURANTS_GEO_SEARCH, param,null,null);
        } else {
            res = HttpClientUtil.doYOGIYOGet(Constant.RESTAURANTS_GEO, param,null,null);
        }
        if (StringUtils.isNotEmpty(res)) {
            JSONObject resJson = JSONObject.parseObject(res);
            JSONArray restaurants = resJson.getJSONArray("restaurants");
            //封装返回的数据
            JSONObject result = new JSONObject();
            result.put("pagination", resJson.getJSONObject("pagination"));
            List<ThirdBrandShopInfo> list = new ArrayList<>();
            for (int i = 0; i < restaurants.size(); i++) {
                JSONObject json = restaurants.getJSONObject(i);
                ThirdBrandShopInfo thirdPartyShopInfo = new ThirdBrandShopInfo();
                this.parseBrandEntity(thirdPartyShopInfo, json);
                thirdPartyShopInfo.setTranslateName(json.getString("name"));
                thirdPartyShopInfo.setLogoUrl(json.getString("logo_url"));
                list.add(thirdPartyShopInfo);
            }
            result.put("restaurants", list);
            return result;
        }
        return null;
    }

    @Override
    public ThirdBrandShopInfo getShopInfo(String id, String lat, String lng) {
        QueryWrapper<ThirdBrandShopInfo> queryWrapper=new QueryWrapper<>();
        queryWrapper.lambda().eq(ThirdBrandShopInfo::getId,id);
        ThirdBrandShopInfo one = this.brandShopInfoService.getOne(queryWrapper);
        if (one == null) {
            one =new ThirdBrandShopInfo();
        }
        HashMap<String,String> map=new HashMap<>();
        map.put("lat", lat);
        map.put("lng", lng);
        String url = Constant.YOGIYO_SHOP_INFO.replace("{restaurant_id}", id);
//        String res = HttpClientUtil.doYOGIYOGet(url, map,null,null);
        String res = HttpClientUtil.specialGet(url,  map);
        JSONObject json= JSONObject.parseObject(res);
        System.out.println("res  "+res);
        this.parseBrandEntity(one, json);
        return one;
    }
    //"AIP_MENU"：rediskey前缀、“300”：缓存时间（s），“selfCacheManager”：所选择的缓存管理器
    @Cacheable(value = "AIP_MENU=300",key = "#p0",cacheManager = "selfCacheManager")
    @Override
    public JSONArray findMenu(String restaurantId) {
        long start = System.currentTimeMillis();
        Map<String, String> param = new HashMap<>();
        param.put("add_photo_menu", "android");
        param.put("add_one_dish_menu", "true");
        param.put("additional_discount_per_menu", "true");
        param.put("order_serving_type", "delivery");
        String url = Constant.MENU.replace("{restaurant_id}", restaurantId);
        String res = HttpClientUtil.specialGet(url, param);
        Log.info("res {}",res);
        JSONArray array=JSONArray.parseArray(res);
        JSONArray resArray=new JSONArray();
        Map<String, Object> menuMap=new HashMap<>();
        long start2 = System.currentTimeMillis();
        int size = array.size();
        for (int i = 0; i< size; i++){
            JSONObject categories =array.getJSONObject(i);
            JSONObject newCategories=new JSONObject();
            newCategories.put("slug",categories.getString("slug"));
            String name = categories.getString("name");
            //newCategories.put("name", name);
            if(categories.getString("ms_type")!=null){
                newCategories.put("ms_type",categories.getString("ms_type"));
            }

            if (StringUtils.isNotEmpty(name)){
                newCategories.put("translate_name",name.replaceAll("(?i)yogiyo",""));
            }

            //翻译菜品分类名称
            if("Photo Menu Items".equals(name)){
                newCategories.put("translate_name","人气推荐");
                menuMap.put(name,"人气推荐");
            }else if("Top 10".equals(name)){
                newCategories.put("translate_name","Top 10");
                menuMap.put(name,"Top 10");
            }else{
                if (name.contains("[요기서 결제시]")) {
                    name = name.replace("[요기서 결제시]", "");
                }
                newCategories.put("translate_name",name);
                menuMap.put(name,name);
        }

            JSONArray itemsArray=categories.getJSONArray("items");
            JSONArray resItemsArray=new JSONArray();
            int size1 = itemsArray.size();
            for (int j = 0; j< size1; j++){
                JSONObject menu=itemsArray.getJSONObject(j);
                JSONObject newMenu=new JSONObject();
                newMenu.put("name",menu.getString("name").replaceAll("(?i)yogiyo",""));
                newMenu.put("image",menu.getString("image"));
//                newMenu.put("image", menuImage);
                newMenu.put("soldout",menu.getBoolean("soldout"));
                //如果商品单价大于Constant.InitPrice，则，涨一千。
                int price = Integer.parseInt(menu.getString("price"));
                int finalPrice = price >= Constant.InitPrice ? price + 1000 : price;
                newMenu.put("price", String.valueOf(finalPrice));
//                newMenu.put("original_image",menu.getString("original_image"));
                newMenu.put("original_image","");
                newMenu.put("id",menu.getLong("id"));
                newMenu.put("slug",menu.getString("slug"));
                newMenu.put("stock_amount", menu.getString("stock_amount"));
                if(menu.getString("discounted_price")!=null){
                    newMenu.put("discounted_price",menu.getLong("discounted_price"));
                }
                if(menu.getString("discounts")!=null){
                    newMenu.put("discounts",menu.getJSONObject("discounts"));
                }
                //判断第三方菜品数据是否已经翻译
                JSONArray menuSubchoices=menu.getJSONArray("subchoices");
                JSONArray resMenuSubchoices=new JSONArray();
                for (int k=0;k<menuSubchoices.size();k++){
                    JSONObject specificationCategories=menuSubchoices.getJSONObject(k);
                    JSONObject newSpecificationCategories=new JSONObject();
                    newSpecificationCategories.put("name",specificationCategories.getString("name").replaceAll("(?i)yogiyo",""));
                    newSpecificationCategories.put("multiple",specificationCategories.getBoolean("multiple"));
                    newSpecificationCategories.put("multiple_count",specificationCategories.getLong("multiple_count"));
                    newSpecificationCategories.put("is_available_quantity",specificationCategories.getBoolean("is_available_quantity"));
                    newSpecificationCategories.put("mandatory",specificationCategories.getBoolean("mandatory"));
                    newSpecificationCategories.put("slug",specificationCategories.getString("slug"));
                    JSONArray subchoicesOptions=specificationCategories.getJSONArray("subchoices");
                    JSONArray ressubchoicesOptions=new JSONArray();
                    for (int p=0;p<subchoicesOptions.size();p++){
                        JSONObject options=subchoicesOptions.getJSONObject(p);
                        JSONObject newOptions=new JSONObject();
                        newOptions.put("name",options.getString("name").replaceAll("(?i)yogiyo",""));
                        newOptions.put("price",options.getString("price"));
                        newOptions.put("id",options.getLong("id"));
                        newOptions.put("soldout",options.getBoolean("soldout"));
                        newOptions.put("slug",options.getString("slug"));
                        ressubchoicesOptions.add(newOptions);
                    }
                    newSpecificationCategories.put("subchoices",ressubchoicesOptions);
                    resMenuSubchoices.add(newSpecificationCategories);
                }
                newMenu.put("subchoices",resMenuSubchoices);
                resItemsArray.add(newMenu);
            }
            newCategories.put("items",resItemsArray);
            resArray.add(newCategories);
        }
        long end2 = System.currentTimeMillis();
        Log.info("===============》遍历数据："+(end2-start2));
        long end = System.currentTimeMillis();
        Log.info("获取菜品用时："+(end-start));
        return resArray;
    }

    @Override
    public JsonResult cart( String restaurantId, String items, Integer addressId, String payType, String mapLatitude, String mapLongitude) {
        //查询用户地址
        ShippingAddress shippingAddress=shippingAddressService.getById(addressId);
        if(shippingAddress==null||StringUtils.isEmpty(shippingAddress.getZipCode())){
            return JsonResult.errorMsg( Constant.NO_ZIPCODE, "地址或地址邮编为空");
        }
        Map<String, Object> paramCart = new HashMap<>();
        paramCart.put("items",items);
        paramCart.put("zip_code",shippingAddress.getZipCode());
        paramCart.put("order_serving_type","delivery");
        paramCart.put("customer_lat",shippingAddress.getMapLatitude());
        paramCart.put("customer_lng",shippingAddress.getMapLongitude());
        Log.info("优惠---加入购物车---店铺编号--------------->" + restaurantId);
        Log.info("优惠---加入购物车---传参--------------->"+paramCart);
        String urlCart = Constant.CART.replace("{restaurant_id}", restaurantId);
//        JSONObject resCart = HttpClientUtil.doYOGIYOPost(urlCart, paramCart,null,null,null);
        JSONObject resCart = HttpClientUtil.specialPost(urlCart, paramCart,null,null);
        Log.info("优惠---加入购物车---响应--------------->"+resCart);
        String cartResult = resCart.getString("result");
        if(cartResult !=null){
            try{
                JSONObject result=resCart.getJSONObject("result");
                if (result.getString("meta") != null) {
                    Map<String, Integer> payletterAmount = getPayletterAmount(restaurantId, result);
                    result.put("discountAmount", payletterAmount.get("discount"));
                    Integer amount = payletterAmount.get("amount");
                    if (amount >= 50000) {
                        return JsonResult.errorMsg( 500, "快餐订单金额请不可大于五万。");
                    }
                    return JsonResult.ok( result);
                }
            }catch (Exception e){
                Log.info("异常为 [{}] ",e.getMessage());
                JSONArray jsonArray = JSONObject.parseArray(cartResult);
                assert jsonArray != null;
                JSONArray jsonArray1 = JSONObject.parseArray(jsonArray.get(0).toString());
                String o = (String) jsonArray1.get(1);
                if ("해당 식당은 요청하신 지역에 배달하지 않습니다.".equals(o)) {
                    return JsonResult.errorMsg( Constant.InvalidAddress, "不可送达的地址");
                }else{
//                    return JsonResult.errorMsg( 500, PaPaGoTranslateUtil.getTransResult(o));
                    return JsonResult.errorMsg( 500, o);
                }
            }

        }
        return JsonResult.errorMsg( 500, cartResult);
    }

    @Override
    public JsonResult submit( String restaurantId, String items, Integer addressId, String comment,
                              String userId, String menuInfo, String payType, String deliveryFee,
                              String contactPhone, String dropRoadAddress, String dropJibunAddress,
                              String detailedAddress, String mapLatitude, String mapLongitude) {
        Log.info("submit::restaurantId = [{}], items = [{}], addressId = [{}], comment = [{}], userId = [{}], menuInfo = [{}], payType = [{}], deliveryFee = [{}], contactPhone = [{}], dropRoadAddress = [{}], dropJibunAddress = [{}], detailedAddress = [{}], mapLatitude = [{}], mapLongitude = [{}]", restaurantId, items, addressId, comment, userId, menuInfo, payType, deliveryFee, contactPhone, dropRoadAddress, dropJibunAddress, detailedAddress, mapLatitude, mapLongitude);
        YogiyoAccountManagementVo yogiyoAccountManagementVo = yogiyoAccountManagementService.selectAccount();
        if (yogiyoAccountManagementVo == null) {
            Log.info(userId + "============>下单时未获取到下单账号");
            return JsonResult.errorMsg(Constant.UnknownSubmitError, "出现未知错误，请联系客服处理");
        }
        ShippingAddress shippingAddress = shippingAddressService.getById(addressId);
        if (shippingAddress == null || StringUtils.isEmpty(shippingAddress.getZipCode())
                || StringUtils.isEmpty(shippingAddress.getAddressElements()) || "[]".equals(shippingAddress.getAddressElements())) {
            return JsonResult.errorMsg(Constant.NO_ZIPCODE, "地址或地址邮编为空");
        }
        yogiyoAccountManagementVo.setUse(1);
        yogiyoAccountManagementVo.setUseDate(new Date());
        //账号更新为使用状态
        redisUtil.set(RedisConstant.USER_YOGIYO_TOKEN + yogiyoAccountManagementVo.getUserName(), yogiyoAccountManagementVo, 6600);
        Map<String, Object> paramCart = new HashMap<>();
        paramCart.put("items", items);
        paramCart.put("zip_code", shippingAddress.getZipCode());
        paramCart.put("order_serving_type", "delivery");
        paramCart.put("customer_lat", shippingAddress.getMapLatitude());
        paramCart.put("customer_lng", shippingAddress.getMapLongitude());
        Log.info("店铺编号--------------->" + restaurantId);
        Log.info("加入购物车---传参--------》" + paramCart);
        String urlCart = Constant.CART.replace("{restaurant_id}", restaurantId);
        JSONObject resCart = HttpClientUtil.specialPost(urlCart, paramCart,yogiyoAccountManagementVo.getCookie(),null);
        Log.info("加入购物车---响应--------》" + resCart);
        String cartResult = resCart.getString("result");
        if (StringUtils.isEmpty(cartResult)) {
            //出现异常，释放账号
            yogiyoAccountManagementVo.setUse(0);
            redisUtil.set(RedisConstant.USER_YOGIYO_TOKEN + yogiyoAccountManagementVo.getUserName(), yogiyoAccountManagementVo, 6600);
            return JsonResult.errorMsg(Constant.UnknownSubmitError, "出现未知错误，请联系客服处理");
        }
        JSONObject jsonObject = new JSONObject();
        JSONObject result = null;
        try {
            result = resCart.getJSONObject("result");
            jsonObject.put("cookie", resCart.getString("cookie"));
            jsonObject.put("delivery_fee", result.getString("delivery_fee"));
            jsonObject.put("sum_items", result.getJSONObject("meta").getString("sum_items"));
            jsonObject.put("sum", result.getJSONObject("meta").getString("sum"));
            jsonObject.put("discount_value", result.getString("discount_value"));
            if (result.getJSONObject("discounts") != null) {
                if (result.getJSONObject("discounts").getString("delivery_fee") != null) {
                    jsonObject.put("discounts_delivery_fee", result.getJSONObject("discounts").getString("delivery_fee"));
                }
            }
            if (result.getJSONObject("discounts") != null) {
                if (result.getJSONObject("discounts").getString("additional") != null) {
                    jsonObject.put("amount", result.getJSONObject("discounts").getJSONObject("additional").getString("amount"));
                    jsonObject.put("per_menu_count", result.getJSONObject("discounts").getJSONObject("additional").getString("per_menu_count"));
                }
            }
        }catch (Exception e) {
            //出现异常，释放账号
            yogiyoAccountManagementVo.setUse(0);
            redisUtil.set(RedisConstant.USER_YOGIYO_TOKEN + yogiyoAccountManagementVo.getUserName(), yogiyoAccountManagementVo, 6600);
            Log.info("异常为 [{}] ", e.getMessage());
            JSONArray jsonArray = JSONObject.parseArray(cartResult);
            assert jsonArray != null;
            JSONArray jsonArray1 = JSONObject.parseArray(jsonArray.get(0).toString());
            String o = (String) jsonArray1.get(1);
            if ("해당 식당은 요청하신 지역에 배달하지 않습니다.".equals(o)) {
                return JsonResult.errorMsg(Constant.InvalidAddress, "不可送达的地址");
            } else if ("주문할 수 없는 메뉴가 포함되어 있습니다. 메뉴를 삭제한 뒤 다시 선택해주세요.".equals(o)) {
                return JsonResult.errorMsg(Constant.CAN_NOT_BUY, "包含无法订购的菜单项。 请删除菜单并重新选择。");
            } else {
                return JsonResult.errorMsg(500, o);
            }
        }
        /*********************************加入购物车end********************************/

        /*********************************下单start********************************/
        Map<String, Object> paramSubmit = new HashMap<>();
        paramSubmit.put("description", shippingAddress.getDropJibunAddress());
        paramSubmit.put("comment", StringUtils.isNotEmpty(comment) ? comment : "");
        this.setParam(restaurantId, paramSubmit, jsonObject);
        paramSubmit.put("address_selector", "");
        paramSubmit.put("name", "Android_6.50.0");
        paramSubmit.put("payment", "ygypay");
        paramSubmit.put("phone", shippingAddress.getContactPhone());
        paramSubmit.put("preorder", "");
        paramSubmit.put("street_number", "호수 " + shippingAddress.getDetailedAddress());
        paramSubmit.put("tos", "false");
        paramSubmit.put("zip_code", shippingAddress.getZipCode());
        paramSubmit.put("safen_number_checked", "false");
        paramSubmit.put("skip_duplicate", "true");
        paramSubmit.put("email", yogiyoAccountManagementVo.getUserName());
        paramSubmit.put("sms_accept", "false");
        paramSubmit.put("centralpayment", "none");
        Map<String, Integer> mapAmount = getPayletterAmount(restaurantId, result);
        //订单金额
        String amount = String.valueOf(mapAmount.get("amount"));
        Log.info("yogiyoAccountManagementVo=======>" + yogiyoAccountManagementVo);
        //获取当前账号下使用次数最少的银行卡信息
        YogiyoBankCard yogiyoBankCard = yogiyoAccountManagementService.selectBankCard(yogiyoAccountManagementVo.getCustomerId(), amount);
        paramSubmit.put("ygypay_token", yogiyoBankCard.getToken());
        paramSubmit.put("card_companies_code", yogiyoBankCard.getCode());
        paramSubmit.put("profile_number", "true");
        paramSubmit.put("password", "");
        paramSubmit.put("sms_verify", "false");
        paramSubmit.put("efinance_agreement", "true");
        paramSubmit.put("order_type", "delivery");
        paramSubmit.put("subscription_discount_amount", "0");
        paramSubmit.put("lat", shippingAddress.getMapLatitude());
        paramSubmit.put("lng", shippingAddress.getMapLongitude());
        JSONArray address = JSONArray.parseArray(shippingAddress.getAddressElements());
        String sido = "";
        String gugun = "";
        String dong = "";
        String ri = "";
        String bunji = "";
        String road = "";
        String building = "";
        for (int i = 0; i < address.size(); i++) {
            JSONObject json = address.getJSONObject(i);
            if ("SIDO".equals(json.getJSONArray("types").getString(0))) {
                sido = json.getString("shortName");
            }
            if ("SIGUGUN".equals(json.getJSONArray("types").getString(0))) {
                gugun = json.getString("shortName");
            }
            if ("DONGMYUN".equals(json.getJSONArray("types").getString(0))) {
                dong = json.getString("shortName");
            }
            if ("RI".equals(json.getJSONArray("types").getString(0))) {
                ri = json.getString("shortName");
            }
            if ("LAND_NUMBER".equals(json.getJSONArray("types").getString(0))) {
                bunji = json.getString("shortName");
            }
            if ("ROAD_NAME".equals(json.getJSONArray("types").getString(0))) {
                road = json.getString("shortName");
            }
            if ("BUILDING_NUMBER".equals(json.getJSONArray("types").getString(0))) {
                building = json.getString("shortName");
            }
        }
        paramSubmit.put("sido", sido);
        paramSubmit.put("gugun", gugun);
        paramSubmit.put("dong", dong);
//        paramSubmit.put("admin_dong", getMapAdminDong(shippingAddress.getMapLongitude() + "," + shippingAddress.getMapLatitude()));
        paramSubmit.put("admin_dong", "대치4동");
        paramSubmit.put("ri", ri);
        paramSubmit.put("bunji", bunji);
        paramSubmit.put("road", road);
        paramSubmit.put("building", building);
        String urlSubmit = Constant.SUBMIT.replace("{restaurant_id}", restaurantId);
        Log.info("下单---店铺的编号--------》" + restaurantId);
        Log.info("下单---传参--------》" + paramSubmit);
        JSONObject resSubmit = HttpClientUtil.specialPost(urlSubmit, paramSubmit, yogiyoAccountManagementVo.getCookie(), yogiyoAccountManagementVo.getAccessToken());
        String res = resSubmit.getString("result");
        Log.info("下单---响应--------》" + res);
        //下单结束，释放账号
        yogiyoAccountManagementVo.setUse(0);
        redisUtil.set(RedisConstant.USER_YOGIYO_TOKEN + yogiyoAccountManagementVo.getUserName(), yogiyoAccountManagementVo, 6600);
        //银行卡使用次数加一
        yogiyoBankCard.setUseCount(1 + yogiyoBankCard.getUseCount());
        yogiyoBankCardService.updateById(yogiyoBankCard);
        //下单返回数据中没有window.location.replace，则说明下单失败
        if (!res.contains("window.location.replace")) {
            try {
                //一般是这种格式的错误：[["sms_verify","SMS verification required"]]
                JSONArray jsonArray = JSONObject.parseArray(res);
                JSONArray jsonArray1 = JSONObject.parseArray(jsonArray.get(0).toString());
                String prompt = (String) jsonArray1.get(0);
                String reason = (String) jsonArray1.get(1);
                if ("sms_verify".equals(prompt)) {
                    return JsonResult.errorMsg( Constant.SubmitVerifyError, "号码未经认证，请认证");
                }
                if ("phone_invalid".equals(prompt)) {
                    return JsonResult.errorMsg( Constant.InvalidPhoneNumber, "请填写正确格式的韩国手机号");
                }
                if ("error_stock".equals(prompt)) {
                    return JsonResult.errorMsg(Constant.InsufficientQuantity, "该商品库存不足");
                }
                if ("nothing".equals(prompt)) {
                    if ("현재 주문이 불가능한 매장입니다. 다른 매장을 이용해주세요.".equals(reason)) {
                        return JsonResult.errorMsg(Constant.NotOpen, "当前店铺不在运营时间内，请换家店铺");
                    }
                    if ("현재 서비스되지 않는 메뉴가 포함되어 있습니다. 주문수정을 통해 메뉴를 확인해주세요.".equals(reason)) {
                        return JsonResult.errorMsg(Constant.NotOpen, "包含当前不提供服务的菜单，请修改菜单后重新下单。");
                    }
                    if ("일회용품(포장 비닐봉투 등)을 한 개 포함하여 주문해야 합니다. 선택한 일회용품의 수량이 1이 아닌 경우 수량을 1로 변경해주세요.".equals(reason)) {
                        return JsonResult.errorMsg(Constant.WithoutPlasticBag, "您必须订购一件一次性物品（如塑料包装袋）。如果选择的一次性物品的数量不是1件，请将数量改为1件。");
                    }
                    if ("요일별 할인이 적용된 메뉴입니다. 메뉴의 가격을 다시 확인후 주문해주세요.".equals(reason)) {
                        return JsonResult.errorMsg(Constant.NotOpen, "包含当前不提供服务的菜单，请修改菜单后重新下单。");
                    } else {
                        return JsonResult.errorMsg(500, "订单异常，请重新下单");
                    }
                }
            } catch (Exception e) {
                Log.info("出现未知错误==>[{}]", res);
                e.printStackTrace();
            }
            return JsonResult.errorMsg( Constant.UnknownSubmitError, "出现未知错误，请联系客服处理");
        }else {
            JSONObject jsonObject1 = new JSONObject();
            String paymentNo = this.analysisHtml(res);
            jsonObject1.put("order", paymentNo);
            //创建支付信息
            ThirdPartyPayInfo thirdPartyPayInfo = new ThirdPartyPayInfo();
            thirdPartyPayInfo.setOrderAddress(shippingAddress.getDropJibunAddress());
            thirdPartyPayInfo.setDetailedAddress(shippingAddress.getDetailedAddress());
            thirdPartyPayInfo.setPhoneNumber(shippingAddress.getContactPhone());
            thirdPartyPayInfo.setComment(comment);
            thirdPartyPayInfo.setShopId(restaurantId);
            thirdPartyPayInfo.setUserId(userId);
            thirdPartyPayInfo.setUserName(yogiyoAccountManagementVo.getUserName());
            thirdPartyPayInfo.setUserCard(yogiyoBankCard.getNumber());
            thirdPartyPayInfo.setPaymentNo(paymentNo);
            thirdPartyPayInfo.setPayStatus(0);
            thirdPartyPayInfo.setThirdPayStatus(0);
            if (StringUtils.isNotEmpty(payType)) {
                thirdPartyPayInfo.setPayType(Integer.parseInt(payType));
            } else {
                thirdPartyPayInfo.setPayType(1);
            }
            if (result.getString("meta") != null) {
                thirdPartyPayInfo.setDiscounts(String.valueOf(mapAmount.get("discount")));
            }
            thirdPartyPayInfo.setDeliveryFree(deliveryFee);
            thirdPartyPayInfo.setAmount(amount);
            thirdPartyPayInfo.setCreateTime(new Date());
            thirdPartyPayInfo.setMenuInfo(menuInfo);
            thirdPartyPayInfo.setChannel(3);
            thirdPartyPayInfoService.save(thirdPartyPayInfo);
            return JsonResult.ok( jsonObject1);
        }
    }

    /**
     * 解析html获取订单号
     * @param html html
     * @return 订单号
     */
    private String analysisHtml(String html){
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("html").select("head").select("script");
        for (int i=0;i<rows.size();i++){
            String script=rows.get(i).toString();
            if(script.contains("window.location.replace")){
                return script.substring(script.indexOf("checkout/")+9,script.lastIndexOf("\""));
            }
        }
        return null;
    }

    @Override
    public IPage<ThirdPartyPayInfo> getOrders(String userId, Integer pageNum, Integer pageSize) {
        return thirdPartyPayInfoService.pageQuery(userId,pageNum,pageSize);
    }

    @Override
    public ThirdPartyPayInfo getOrderDetail(String orderId, String paymentNo) {
        QueryWrapper<ThirdPartyPayInfo> queryWrapper=new QueryWrapper<>();
        queryWrapper.lambda().eq(StringUtils.isNotEmpty(orderId),ThirdPartyPayInfo::getThirdOrderId,orderId);
        queryWrapper.lambda().eq(StringUtils.isNotEmpty(paymentNo),ThirdPartyPayInfo::getPaymentNo,paymentNo);
        ThirdPartyPayInfo thirdPartyPayInfo = thirdPartyPayInfoService.getOne(queryWrapper);
        if(thirdPartyPayInfo==null){
            return null;
        }

        if(StringUtils.isEmpty(thirdPartyPayInfo.getUserName())){
            thirdPartyPayInfo.setUserName("804498358@qq.com");
        }
        if(StringUtils.isEmpty(orderId)){
            orderId=thirdPartyPayInfo.getThirdOrderId();
        }
        YogiyoAccountManagementVo entity=(YogiyoAccountManagementVo)redisUtil.get(RedisConstant.USER_YOGIYO_TOKEN+thirdPartyPayInfo.getUserName());
        String res = HttpClientUtil.doYOGIYOGet(Constant.ORDER_ID+orderId, null,entity.getAccessToken(),null);
        if(StringUtils.isNotEmpty(res)){
            JSONObject orderInfo=JSONObject.parseObject(res);
            String status_msg = orderInfo.getString("status_msg");
            Log.info("对于订单状态，韩文为[{}]", status_msg);
            if ("주문완료".equals(status_msg)) {
                orderInfo.put("status_msg_translate", "已下单");
            }else if("주문취소".equals(status_msg)){
                orderInfo.put("status_msg_translate", "订单已取消");
            }else if("주문 확인".equals(status_msg)){
                orderInfo.put("status_msg_translate", "确认订单中");
            }else if("배달 중".equals(status_msg)){
                orderInfo.put("status_msg_translate", "送货中");
            }else if("배달완료".equals(status_msg)){
                orderInfo.put("status_msg_translate", "送货完毕");
            }else if ("주문 접수 대기 중".equals(status_msg)) {
                orderInfo.put("status_msg_translate", "正在等待订单");
            }else {
                orderInfo.put("status_msg_translate", status_msg);
            }
            JSONArray menuItems=orderInfo.getJSONArray("menu_items");
            JSONArray newMenuItems=new JSONArray();
            for (int i=0;i<menuItems.size();i++){
                JSONObject jsonObject=menuItems.getJSONObject(i);
                String name = jsonObject.getString("name");
                String tName;
                tName = (String) redisUtil.get(RedisConstant.THIRD_PARTY_BRAND_MENU + name);
                if (tName == null) {
                    tName=(String) redisUtil.get(RedisConstant.THIRD_PARTY_MENU_TRANSLATE+name);
                }

//                String tName=(String) redisUtil.get(RedisConstant.THIRD_PARTY_MENU_TRANSLATE+name);
                //判断菜品名称是否已经翻译
                if (StringUtils.isNotEmpty(tName)) {
                    jsonObject.put("name_translate",tName);
                }else{
                    jsonObject.put("name_translate",name);
                }
                JSONArray flavors=jsonObject.getJSONArray("flavors");
                JSONArray newFlavors=new JSONArray();
                for(int j=0;j<flavors.size();j++){
                    JSONObject flavorsObject=flavors.getJSONObject(j);
                    String flavorsName = flavorsObject.getString("name");
                    String tFlavorsName;
                    tFlavorsName=(String) redisUtil.get(RedisConstant.THIRD_PARTY_BRAND_MENU+flavorsName);
                    if (tFlavorsName == null) {
                        tFlavorsName=(String) redisUtil.get(RedisConstant.THIRD_PARTY_MENU_TRANSLATE+flavorsName);
                    }
//                    String tFlavorsName=(String) redisUtil.get(RedisConstant.THIRD_PARTY_MENU_TRANSLATE+flavorsName);
                    //判断菜品规则名称是否已经翻译
                    if (StringUtils.isNotEmpty(tFlavorsName)) {
                        flavorsObject.put("name_translate",tFlavorsName);
                    }else{
                        flavorsObject.put("name_translate",flavorsName);
                    }
                    newFlavors.add(flavorsObject);
                }
                jsonObject.put("flavors",newFlavors);
                newMenuItems.add(jsonObject);
            }
            orderInfo.put("menu_items",newMenuItems);
            thirdPartyPayInfo.setOrderInfo(orderInfo);
            thirdPartyPayInfo.setTranslateShopName(this.getById(thirdPartyPayInfo.getShopId()).getTranslateName());
        }
        return thirdPartyPayInfo;
    }

    @Override
    public Map<String, String> getShopDiscount(String restaurantId) {
        String url = Constant.getShopDiscount.replace("{restaurant_id}", restaurantId);
        String s = HttpClientUtil.doYOGIYOGet(url, null, null,null);
        if (StringUtils.isEmpty(s)) {
            return null;
        }
        HashMap<String, String> map=new HashMap<>();
        JSONObject jsonObject = JSONObject.parseObject(s);
        JSONObject discounts = JSONObject.parseObject(jsonObject.getString("discounts"));
        JSONObject discount = JSONObject.parseObject(discounts.getString("discount"));
        String discountPercent = discount.getString("discount_percent");
        //规定时间内discount_percent未加在内。百分比折扣
        if (StringUtils.isNotEmpty(discountPercent)) {
            map.put("discount_percent", discountPercent);
            return map;
        }
        //固定金额折扣
        JSONObject additional = JSONObject.parseObject(discounts.getString("additional"));
        if (additional != null) {
            JSONObject delivery = JSONObject.parseObject(additional.getString("delivery"));
            if (delivery != null) {
                String amount = delivery.getString("amount");
                map.put("delivery", amount);
                return map;
            }
        }
        Log.info("优惠方式传参如下  ===》 "+map);
        return null;
    }

    @Override
    public JsonResult paymentCallback(String paymentNo, String userId) {
        Log.info("paymentCallback传参============{}======{}",paymentNo,userId);
        //查询订单信息
        ThirdPartyPayInfo payment = thirdPartyPayInfoService.getOne(new QueryWrapper<ThirdPartyPayInfo>().eq("payment_no", paymentNo));
        if (Integer.parseInt(payment.getAmount()) >= 50000) {
            //执行自家退款逻辑
            Map<String, String> refundParam=new HashMap<>();
            refundParam.put("paymentNo",payment.getPaymentNo());
            String refundRes;
            //支付方式（1：payletter支付，2：微信小程序支付，3：APP微信支付，4：APP支付宝支付）
            if(payment.getPayType()==1){
                refundRes=HttpClientUtil.doPost(Constant.PAY_LETTER_REFUND, refundParam);
            }else{
                refundRes=HttpClientUtil.doPost(Constant.WX_REFUND, refundParam);
            }
            Log.info("自家退款接口返回==============>"+refundRes);
            Log.info("商家主动退单，正在处理退款流程，订单号为[{}]，退款结果为[{}]",payment.getPaymentNo(),refundRes);
            if(StringUtils.isEmpty(refundRes)){
                payment.setPayStatus(4);
                payment.setOrderStatus("大于5w退单，但是给用户退款失败");
                thirdPartyPayInfoService.updateById(payment);
            }
            JSONObject wxResJson=JSONObject.parseObject(refundRes);
            if("200".equals(wxResJson.getString("code"))){
                payment.setPayStatus(2);
                payment.setOrderStatus("大于5w退单，已为用户成功退款");
                payment.setRefundTime(new Date());
                thirdPartyPayInfoService.updateById(payment);
            }
            return JsonResult.ok();
        }
        QueryWrapper<YogiyoAccountManagement> managementWrapper= new QueryWrapper<>();
        managementWrapper.lambda().eq(YogiyoAccountManagement::getUserName, payment.getUserName());
        YogiyoAccountManagement yogiyoAccountManagement=yogiyoAccountManagementService.list(managementWrapper).get(0);
        //yogiyo支付逻辑
        JSONObject jsonObject3 = null;
        try {
            String order = HttpClientUtil.doYOGIYOGet("https://payo.yogiyo.co.kr/v1/payment/checkout/" + paymentNo, null, null,null);
            Document parse = Jsoup.parse(order);
            Elements select1 = parse.select("input[type='hidden']");
            HashMap<String,String> map=new HashMap<>();
            map.put("mid", select1.select("input[name='mid']").val());
            map.put("wpayUserKey", select1.select("input[name='wpayUserKey']").val());
            map.put("wpayToken", select1.select("input[name='wpayToken']").val());
            map.put("ci", "");
            map.put("payMethod", "");
            map.put("backCardCode", "");
            map.put("oid", paymentNo);
            map.put("goodsName", select1.select("input[name='goodsName']").val());
            map.put("goodsPrice", select1.select("input[name='goodsPrice']").val());
            map.put("buyerName", "customer");
            map.put("buyerTel", select1.select("input[name='buyerTel']").val());
            map.put("buyerEmail", select1.select("input[name='buyerEmail']").val());
            map.put("cardQuota", select1.select("input[name='cardQuota']").val());
            map.put("couponCode", "");
            map.put("cardInterest", "");
            map.put("flagPin", "N");
            map.put("flagPinMsg", "");
            map.put("returnUrl", select1.select("input[name='returnUrl']").val());
            map.put("signature", select1.select("input[name='signature']").val());
            map.put("cshRecpSave", "");
            map.put("cshRecpCode", "");
            map.put("cshRecpInfo", "");
            JSONObject jsonObject = HttpClientUtil.doYOGIYOPost("https://wpay.inicis.com/ygypay/u/v2/payreqauth", map, null, null,null);
            String result = JSONObject.toJSONString(jsonObject.getString("result"));
            String s1 = StringEscapeUtils.unescapeJava(result);
            Document parse1 = Jsoup.parse(s1);
            Elements select = parse1.select("#form0");
            HashMap<String,String> map1=new HashMap<>();
            map1.put("wtid", select.select("input[name='wtid']").val());
            map1.put("mid", select.select("input[name='mid']").val());
            map1.put("serviceno", select.select("input[name='serviceno']").val());
            map1.put("uskey", select.select("input[name='uskey']").val());
            map1.put("wdata", yogiyoAccountManagement.getWdata());
            map1.put("ukey", yogiyoAccountManagement.getUkey());
            JSONObject jsonObject2 = HttpClientUtil.doYOGIYOPost("https://wpay.inicis.com/ygypay/u/payreqauthAction/", map1, null, null,null);
            String result1 = StringEscapeUtils.unescapeJava(jsonObject2.getString("result"));
            Document parse2 = Jsoup.parse(result1);
            String attr = Objects.requireNonNull(parse2.select("a").first()).attr("href");
            Log.info("支付完成，跳转到支付信息页面=============>"+attr);
            String s = HttpClientUtil.doYOGIYOGet(attr, null, null,null);
            Log.info("支付信息页面，获取支付信息=============>"+s);
            String orderInfo = s.substring(s.indexOf("result = {") + 9, s.lastIndexOf("\"};") + 3);
            String result2 = orderInfo.substring(0, orderInfo.length() - 1);
            jsonObject3 = JSONObject.parseObject(result2);
        } catch (Exception e) {
            Log.info("支付中出现异常，需要查询历史订单,订单信息为：{}",payment);
            redisUtil.set(RedisConstant.exceptionPayment + payment.getPaymentNo(), payment, 15);
            return JsonResult.errorMsg(500, "支付异常");
        }
        if ((boolean) jsonObject3.get("status")) {
            String order_number = jsonObject3.getString("order_number");
            String order_id = jsonObject3.getString("order_id");
            String amount = jsonObject3.getString("amount");
            //更新订单
            payment.setYogiyoAmount(amount);
            payment.setThirdOrderId(order_id);
            payment.setThirdOrderNumber(order_number);
            payment.setThirdPayStatus(1);
            payment.setPayTime(new Date());
            payment.setOrderStatus("正常支付");
            Log.info("有十分钟查询订单产生，且已支付，将其放进redis，key为[{}],value为[{}]",RedisConstant.PaidOrderPendingId + ":" + order_id,payment);
            Log.info("有三十分钟查询订单产生，且已支付，将其放进redis，key为[{}],value为[{}]",RedisConstant.paidOrderIn30Min + ":" + order_id,payment);
            redisUtil.set(RedisConstant.PaidOrderPendingId + ":" + order_id, payment, 600);
            redisUtil.set(RedisConstant.paidOrderIn30Min + ":" + order_id, payment, 1800);
            thirdPartyPayInfoService.updateById(payment);
            //更新卡余额
            QueryWrapper<YogiyoBankCard> wrapper= new QueryWrapper<>();
            wrapper.lambda().eq(YogiyoBankCard::getNumber, payment.getUserCard());
            List<YogiyoBankCard> list = yogiyoBankCardService.list(wrapper);
            YogiyoBankCard yogiyoBankCard=list.get(0);
            BigDecimal cardAmount = new BigDecimal(yogiyoBankCard.getBalance());
            BigDecimal useAmount = new BigDecimal(amount);
            yogiyoBankCard.setBalance((cardAmount.subtract(useAmount)).toString());
            yogiyoBankCardService.updateById(yogiyoBankCard);
            return JsonResult.ok();
        }else{
            Log.info("支付后出现异常，需要check,订单信息为：{}", payment);
            redisUtil.set(RedisConstant.exceptionPayment + payment.getPaymentNo(), payment, 15);
            return JsonResult.errorMsg(500, "支付异常");
        }
    }

    @Override
    public JsonResult cancelOrder( String userId, String orderNumber) {
        Log.info("cancelOrder::userId = [{}], orderNumber = [{}]",userId, orderNumber);
        QueryWrapper<ThirdPartyPayInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("third_order_number", orderNumber);
        wrapper.eq("user_id", userId);
        ThirdPartyPayInfo thirdPartyPayInfo=thirdPartyPayInfoService.getOne(wrapper);
        if(thirdPartyPayInfo==null){
            Log.info("取消订单时，订单不存在==============[{}]",orderNumber);
            return JsonResult.errorMsg(500,"订单不存在");
        }else{
            if (thirdPartyPayInfo.getPayStatus() == 2) {
                Log.info("取消订单时，单已退款,请勿重复退款==============[{}]",orderNumber);
                return JsonResult.errorMsg(Constant.AlreadyCanceled, "订单已退款,请勿重复退款");
            }
        }
        //四小时只允许退款一次
        QueryWrapper<ThirdPartyPayInfo> wrapperCount = new QueryWrapper<>();
        wrapperCount.eq("user_id", userId);
        wrapperCount.eq("third_pay_status", 2);
        //此处选择订单创建时间做当天是否退过款的条件是因为：创建时间不会为空，能退款的时间很短，基本不会跨天
        wrapperCount.between("create_time",DateUtils.fourHoursAgo(),new Date());
        if(thirdPartyPayInfoService.count(wrapperCount)>0){
            //用户白名单
            if(!("630842189d6a4a2888670fb90687c260".equals(userId)||"f2a940625bab4aa39eb1d6f47882887a".equals(userId)||"7a875dd8f5de46f7896e3fbcee0838e0".equals(userId)||"a88701d2ecce4a0d8e8544295e79e9f3".equals(userId))){
                Log.info("抱歉，您在最近已经取消过一次订单，请勿频繁取消=============[{}]",orderNumber);
                return JsonResult.errorMsg(Constant.YOGIYO_REFUND, "您在最近已经取消过一次订单，请勿频繁取消");
            }
        }

        HashMap<String,String> param = new HashMap<>();
        param.put("order_number",orderNumber);
        YogiyoAccountManagementVo entity=(YogiyoAccountManagementVo)redisUtil.get(RedisConstant.USER_YOGIYO_TOKEN+thirdPartyPayInfo.getUserName());
        Log.info("取消订单传值=============="+param);
        JSONObject res = HttpClientUtil.doYOGIYOPost(Constant.CANCEL_ORDER, param, entity.getCookie(),  entity.getAccessToken(),null);
        Log.info("YOGIYO退款接口返回==================>"+res);
        if(res.getString("result")!=null){
            JSONObject result=res.getJSONObject("result");
            if("OK".equals(result.getString("result"))){
                thirdPartyPayInfo.setThirdPayStatus(2);
                thirdPartyPayInfo.setRefundTime(new Date());
                thirdPartyPayInfo.setOrderStatus("用户主动取消订单，ygy已取消");
                thirdPartyPayInfo.setCancelReason("用户已主动取消该笔订单");
                thirdPartyPayInfoService.updateById(thirdPartyPayInfo);
                //更新卡余额
                QueryWrapper<YogiyoBankCard> cardWrapper= new QueryWrapper<>();
                cardWrapper.lambda().eq(YogiyoBankCard::getNumber, thirdPartyPayInfo.getUserCard());
                List<YogiyoBankCard> list = yogiyoBankCardService.list(cardWrapper);
                YogiyoBankCard yogiyoBankCard=list.get(0);
                BigDecimal cardAmount = new BigDecimal(yogiyoBankCard.getBalance());
                BigDecimal useAmount = new BigDecimal(thirdPartyPayInfo.getYogiyoAmount());
                yogiyoBankCard.setBalance((cardAmount.add(useAmount)).toString());
                yogiyoBankCardService.updateById(yogiyoBankCard);
                //执行自家退款逻辑
                Map<String, String> refundParam=new HashMap<>();
                refundParam.put("paymentNo",thirdPartyPayInfo.getPaymentNo());
                String refundRes;
                //支付方式（1：payletter支付，2：微信小程序支付，3：APP微信支付，4：APP支付宝支付）
                if(thirdPartyPayInfo.getPayType()==1){
                    refundRes=HttpClientUtil.doPost(Constant.PAY_LETTER_REFUND, refundParam);
                }else{
                    refundRes=HttpClientUtil.doPost(Constant.WX_REFUND, refundParam);
                }
                Log.info("自家退款接口返回==============>"+refundRes);
                if(StringUtils.isEmpty(refundRes)){
                    thirdPartyPayInfo.setPayStatus(4);
                    thirdPartyPayInfo.setOrderStatus("用户主动取消订单，但是给用户退款失败");
                    thirdPartyPayInfoService.updateById(thirdPartyPayInfo);
                    return JsonResult.errorMsg(500,"请求接口失败");
                }
                JSONObject wxResJson=JSONObject.parseObject(refundRes);
                if("200".equals(wxResJson.getString("code"))){
                    thirdPartyPayInfo.setPayStatus(2);
                    thirdPartyPayInfo.setRefundTime(new Date());
                    thirdPartyPayInfo.setOrderStatus("用户主动取消订单，ygy已取消,wx已退款");
                    thirdPartyPayInfoService.updateById(thirdPartyPayInfo);
                    return JsonResult.ok();
                }else{
                    return JsonResult.errorMsg(wxResJson.getIntValue("code"),wxResJson.getString("msg"));
                }
            }else{
                //退款失败
                thirdPartyPayInfo.setThirdPayStatus(4);
                thirdPartyPayInfo.setOrderStatus("用户主动取消订单，但是ygy取消失败");
                //商家已接单，退款失败
                if("고객님 죄송합니다. 해당 주문은 이미 접수가 완료되어 취소가 불가능합니다.".equals(result.getString("message"))){
                    thirdPartyPayInfo.setThirdPayStatus(5);
                    thirdPartyPayInfo.setOrderStatus("商家已接单，退款失败");
                }
                thirdPartyPayInfoService.updateById(thirdPartyPayInfo);
                return JsonResult.errorMsg(500,result.getString("message"));
            }
        }
        return JsonResult.errorMsg();
    }

    @Override
    public JsonResult getYogiyoShopInfo( String restaurantId, String lon, String lat) {
        HashMap<String,String> map=new HashMap<>();
        map.put("lat", lat);
        map.put("lng", lon);
        String shopInfoUrl = Constant.YOGIYO_SHOP_INFO.replace("{restaurant_id}", restaurantId);
        String shopInfo = HttpClientUtil.specialGet(shopInfoUrl, map);
        JSONObject jsonObject = JSONObject.parseObject(shopInfo);
        String open_time_description = jsonObject.getString("open_time_description");
        boolean is_available_delivery = (boolean)jsonObject.get("is_available_delivery");
        String delivery_fee = jsonObject.getString("delivery_fee");
        HashMap<String,Object> resultMap=new HashMap<>();
        resultMap.put("open_time_description",open_time_description);
        resultMap.put("delivery_fee", delivery_fee);
        resultMap.put("is_available_delivery", is_available_delivery);
        return JsonResult.ok(resultMap);
    }

    @Override
    public JsonResult sendVerifyCode(String phoneNumber) {
        Log.info("发送验证号码传参============{}", phoneNumber);
        try{
            Map map=new HashMap();
            JSONObject jsonObject = HttpClientUtil.doYOGIYOPost(Constant.sendVeriCodeUrl + phoneNumber+"/",map, null, null,null);
            Log.info("发送验证号码响应============{}", jsonObject.toJSONString());
            String result = jsonObject.getString("result");
            JSONObject json = JSONObject.parseObject(result);
            String result1 = json.getString("result");
            if ("banned_number".equals(result1)) {
                return JsonResult.errorMsg( Constant.TooMuchVerifyCode, "验证码发送次数过多，今日已被暂时冻结，请明日再试");
            } else if ("dup_veri_trial".equals(result1)) {
                return JsonResult.errorMsg( Constant.verifiedNumber, "号码已经认证过了，请勿重复认证");
            } else {
                return JsonResult.ok( "验证号码已发送，请注意查收");
            }
        }catch (Exception e){
            e.printStackTrace();
            return JsonResult.errorMsg( Constant.TooMuchVerifyCode, "功能维护中,暂不支持手机号码认证");
        }
    }

    @Override
    public JsonResult verifyCode(String phoneNumber, String code) {
        Map<String, String> map = new HashMap<>();
        map.put("code", code);
        Log.info("验证号码入参============phoneNumber：{},code:{}", phoneNumber,code);
        JSONObject jsonObject = HttpClientUtil.doYOGIYOPost(Constant.verifyCodeUrl + phoneNumber+"/", map, null, null,null);
        Log.info("验证号码出参============{}", jsonObject.toJSONString());
        String result = jsonObject.getString("result");
        JSONObject json = JSONObject.parseObject(result);
        String result1 = json.getString("result");
        if ("true".equals(result1)) {
            return JsonResult.ok( "号码已被验证，可以正常下单");
        }else{
            return JsonResult.errorMsg( Constant.InvalidVerifyCode, "验证失败，请填写正确的验证码或重试");
        }
    }

    /**
     * 计算优惠
     */
    public void setParam(String restaurantId,Map<String, Object> map,JSONObject cartInfo) {
        Map<String, String> shopDiscount = this.getShopDiscount(restaurantId);
        String delivery_fee = cartInfo.getString("delivery_fee");
        String discounts_delivery_fee = cartInfo.getString("discounts_delivery_fee");
        if (StringUtils.isNotEmpty(delivery_fee)) {
            map.put("delivery_fee", delivery_fee);
        }
        if (StringUtils.isNotEmpty(discounts_delivery_fee)) {
            if ("0".equals(discounts_delivery_fee)) {
                map.put("delivery_fee_discount", String.valueOf(0));
            }else {
                map.put("delivery_fee_discount", "-"+discounts_delivery_fee);
            }
        }
        if (shopDiscount != null) {
            if (shopDiscount.containsKey("discount_percent")) {
                String discount_percent = shopDiscount.get("discount_percent");
                if (StringUtils.isNotEmpty(discount_percent)) {
                    map.put("discount_percent", discount_percent);
                }
            }
            if (shopDiscount.containsKey("delivery")) {
                String amount = cartInfo.getString("amount");
                String per_menu_count = cartInfo.getString("per_menu_count");
                if (StringUtils.isNotEmpty(amount) && StringUtils.isNotEmpty(per_menu_count)) {
                    int i = Integer.parseInt(amount);
                    int i1 = Integer.parseInt(per_menu_count);
                    int sum= -i * i1;
                    map.put("additional_discount_per_menu",String.valueOf(sum) );
                }
            }
        }
    }

    /**
     * 根据经纬度获取行政洞
     * @param coords 经纬度
     * @return 行政洞
     */
    private String getMapAdminDong(String coords){
        String map=null;
        String MAP_URL = "https://naveropenapi.apigw.ntruss.com/map-reversegeocode/v2/gc?output=json&coords="+coords;
        String res=HttpClientUtil.getMap(MAP_URL);
        JSONObject json=JSONObject.parseObject(res);
        JSONArray arr=json.getJSONArray("results");
        for (int i=0;i<arr.size();i++){
            JSONObject results=arr.getJSONObject(i);
            if("A".equals(results.getJSONObject("code").getString("type"))){
                JSONObject region=results.getJSONObject("region");
                map=region.getJSONObject("area3").getString("name");
            }else if("S".equals(results.getJSONObject("code").getString("type"))){
                JSONObject region=results.getJSONObject("region");
                map=region.getJSONObject("area3").getString("name");
            }
        }
        return map;
    }


    private void parseBrandEntity(ThirdBrandShopInfo thirdPartyShopInfo, JSONObject json) {
        thirdPartyShopInfo.setId(json.get("id")+"");
        thirdPartyShopInfo.setShopName(json.getString("name"));
        thirdPartyShopInfo.setLat(json.getString("lat"));
        thirdPartyShopInfo.setLng(json.getString("lng"));
        thirdPartyShopInfo.setBegin(json.getString("begin"));
        thirdPartyShopInfo.setEnd(json.getString("end"));
        thirdPartyShopInfo.setMinOrderAmount(json.getLong("min_order_amount"));
        thirdPartyShopInfo.setPhone(json.getString("phone"));
        thirdPartyShopInfo.setAddress(json.getString("address"));
        thirdPartyShopInfo.setLogoUrl(json.getString("logo_url"));
        thirdPartyShopInfo.setThumbnailUrl(json.getString("thumbnail_url"));
        thirdPartyShopInfo.setRestaurantType(json.getString("restaurant_type"));
        thirdPartyShopInfo.setOpen(json.getBoolean("open"));
        String is_deliverable = json.getString("is_deliverable");
        if (StringUtils.isNotEmpty(is_deliverable)) {
            if (json.getBoolean("is_deliverable")) {
                thirdPartyShopInfo.setDeliverable(true);
            }else{
                thirdPartyShopInfo.setDeliverable(false);
            }
        }
        if (StringUtils.isNotEmpty(json.getString("distance"))) {
            String distance = json.getString("distance");
            thirdPartyShopInfo.setDistance(distance.substring(0, distance.indexOf(".") + 2));
        }
        if (StringUtils.isNotEmpty(json.getString("estimated_delivery_time"))) {
            thirdPartyShopInfo.setEstimatedDeliveryTime(json.getString("estimated_delivery_time").replace("분", "分钟"));
        }
        thirdPartyShopInfo.setAdjustedDeliveryFee(json.getString("adjusted_delivery_fee"));
        if(StringUtils.isNotEmpty(json.getString("background_url"))){
            thirdPartyShopInfo.setBackgroundUrl(json.getString("background_url"));
        }
    }

    /**
     * 获取payletter上应该展示的金额
     * @param cartInfoJSON 购物车数据
     * @return payletter金额
     */
    public Map<String,Integer> getPayletterAmount(String id,JSONObject cartInfoJSON) {
        Map<String,Integer> map=new HashMap<>();
        int additionalAmount=0;
        JSONArray items = cartInfoJSON.getJSONArray("items");
        for (Object item : items) {
            JSONObject singleMenu = (JSONObject) item;
            int price = Integer.parseInt(singleMenu.getString("price"));
            int quantity = Integer.parseInt(singleMenu.getString("quantity"));
            if (price >= Constant.InitPrice) {
                additionalAmount = additionalAmount + quantity;
            }
        }
        JSONObject meta = cartInfoJSON.getJSONObject("meta");
        int delivery_fee;
        int additionalSum=0;
        delivery_fee = Integer.parseInt(cartInfoJSON.getString("delivery_fee"));
        Log.info("当前店铺编号为：[{}] , 店铺additionalSum==[{}]",id,additionalSum);
        int discount_value = Integer.parseInt(cartInfoJSON.getString("discount_value"));
        int sum = Integer.parseInt(meta.getString("sum"));
        int sum_items = Integer.parseInt(meta.getString("sum_items"));
        additionalSum=sum;
        int discountDeliveryFee=0;
        JSONObject discounts = cartInfoJSON.getJSONObject("discounts");
        if (discounts != null) {
            int aa;
            int ap;
            int discountMenu=0;
            String dis_delivery_fee = discounts.getString("delivery_fee");
            discountDeliveryFee = StringUtils.isEmpty(dis_delivery_fee) ? 0 : Integer.parseInt(dis_delivery_fee);
            JSONObject additional = discounts.getJSONObject("additional");
            if (additional != null) {
                aa = StringUtils.isNotEmpty(additional.getString("amount")) ? Integer.parseInt(additional.getString("amount")) : 0;
                ap = StringUtils.isNotEmpty(additional.getString("per_menu_count")) ? Integer.parseInt(additional.getString("per_menu_count")) : 1;
                if (aa!=0 && ap!=0) {
                    discountMenu = aa * ap;
                }
            }
            int i = additionalSum + discount_value - discountMenu;
            Log.info("这笔订单有discount，这笔订单原本菜品+配送费的价格是 [{}] , 使用优惠券后，最终的价格是 [{}]",sum_items+delivery_fee, i);
            Log.info("优惠价格为：{}={}-{}" ,discount_value - discountMenu,discount_value,discountMenu);
            Log.info("计算公式为 [{}]+[{}]-[{}]",additionalSum,discount_value,discountMenu);
            Log.info("[{}]-[{}]-[{}]=[{}]",sum_items,delivery_fee,i,i-sum_items - delivery_fee);
            map.put("amount", i);
            map.put("discount", i-sum_items - delivery_fee );
            return map;
        }
        int i = additionalSum + discount_value - discountDeliveryFee;
        Log.info("这笔订单没有discount，这笔订单原本菜品+配送费的价格是 [{}] , 使用优惠券后，最终的价格是 [{}]",sum_items+delivery_fee, i);
        Log.info("优惠价格为：{}={}-{}" ,discount_value - discountDeliveryFee,discount_value,discountDeliveryFee);
        Log.info("计算公式为 [{}]+[{}]-[{}]",additionalSum,discount_value,discountDeliveryFee);
        Log.info("[{}]-[{}]-[{}]=[{}]",sum_items,delivery_fee,i,i-sum_items - delivery_fee );
        map.put("amount", i);
        map.put("discount", i-sum_items - delivery_fee );
        return map;
    }
}
