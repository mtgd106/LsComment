package com.vkls.service;

import com.vkls.dto.Result;
import com.vkls.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    //Result createVoucherOrder(Long voucherId);
    void createVoucherOrder(VoucherOrder voucherOrder);
}
