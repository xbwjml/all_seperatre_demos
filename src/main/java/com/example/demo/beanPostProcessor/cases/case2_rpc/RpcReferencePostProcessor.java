package com.example.demo.beanPostProcessor.cases.case2_rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Proxy;

/**
 * 【Case 2】模拟 Dubbo @DubboReference 的远程代理注入处理器
 *
 * <p><b>面试要点：</b>
 * <ul>
 *   <li>Dubbo / gRPC / Thrift 等 RPC 框架的客户端注入，核心都是这个模式：
 *       BPP 扫描注解 → JDK 动态代理创建 stub → 反射注入字段。</li>
 *   <li>使用 {@code postProcessBeforeInitialization}，在 {@code @PostConstruct} 前完成注入，
 *       保证 Bean 初始化时已经可以正常调用远程方法。</li>
 *   <li>代理内部的真实逻辑：序列化请求 → 注册中心寻址（Nacos/ZK）→ 负载均衡
 *       → Netty 建立长连接 → 发送请求 → 等待响应 → 反序列化。</li>
 *   <li>与 {@code @Autowired} 的关键区别：@Autowired 从 Spring 容器找 Bean，
 *       @RpcReference 从注册中心找服务节点，注入的是网络代理对象。</li>
 * </ul>
 * </p>
 */
@Component
public class RpcReferencePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RpcReferencePostProcessor.class);

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            RpcReference annotation = field.getAnnotation(RpcReference.class);
            if (annotation == null) {
                return;
            }
            if (!field.getType().isInterface()) {
                throw new IllegalArgumentException(
                        "@RpcReference 只能标注在接口类型字段上: " + field);
            }
            Object proxy = createRpcProxy(field.getType(), annotation, beanName, field.getName());
            field.setAccessible(true);
            field.set(bean, proxy);

            log.info("[RpcReference] beanName='{}' field='{}' → 注入远程代理 [version={}, group={}, timeout={}ms]",
                    beanName, field.getName(),
                    annotation.version(), annotation.group(), annotation.timeout());
        });
        return bean;
    }

    /**
     * 创建 JDK 动态代理，模拟远程服务调用。
     *
     * <p>生产环境此处替换为真实 RPC 框架的 stub 创建逻辑，例如：
     * <pre>
     *   // Dubbo
     *   ReferenceConfig&lt;T&gt; ref = new ReferenceConfig&lt;&gt;();
     *   ref.setInterface(serviceInterface);
     *   ref.setVersion(annotation.version());
     *   ref.setGroup(annotation.group());
     *   return ref.get();
     *
     *   // gRPC
     *   ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).build();
     *   return ServiceGrpc.newBlockingStub(channel);
     * </pre>
     * </p>
     */
    @SuppressWarnings("unchecked")
    private <T> T createRpcProxy(Class<?> serviceInterface, RpcReference annotation,
                                  String beanName, String fieldName) {
        return (T) Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[]{serviceInterface},
                (proxy, method, args) -> {
                    log.info("[RPC调用] {}.{}() → 远程: interface={}, version={}, timeout={}ms",
                            beanName, fieldName,
                            serviceInterface.getSimpleName(),
                            annotation.version(),
                            annotation.timeout());

                    // 模拟网络延迟
                    // Thread.sleep(20);

                    // 按返回类型给出 mock 值，生产环境这里是真实 RPC 调用
                    return mockReturnValue(method.getReturnType(), args);
                }
        );
    }

    private Object mockReturnValue(Class<?> returnType, Object[] args) {
        if (returnType == String.class) {
            return "MockResult_" + (args != null && args.length > 0 ? args[0] : "N/A");
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Boolean.TRUE;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == long.class || returnType == Long.class) {
            return 0L;
        }
        return null;
    }
}
