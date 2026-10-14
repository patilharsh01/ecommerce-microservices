package com.harshalpatil.ecommerce.product.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fills an empty catalog with demo products so the dashboard has something to sell.
 * Off by default; docker-compose turns it on. Kept out of Flyway on purpose: demo data
 * doesn't belong in the schema history of a real environment.
 */
@Component
@ConditionalOnBooleanProperty("catalog.demo-data.enabled")
@RequiredArgsConstructor
@Slf4j
class DemoCatalogSeeder implements ApplicationRunner {

    private final ProductRepository products;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (products.count() > 0) {
            return;
        }
        List<Product> demo = List.of(
                product("KB-75-HS", "Mechanical Keyboard 75%", "Hot-swappable switches, gasket mount, PBT keycaps", "6499.00", 40),
                product("MS-WL-PRO", "Wireless Mouse Pro", "26K DPI sensor, 70 hour battery, 63 g", "3999.00", 60),
                product("MN-27-4K", "27\" 4K Monitor", "IPS, 144 Hz, USB-C 90 W power delivery", "32999.00", 12),
                product("HP-ANC-1", "Noise-Cancelling Headphones", "Adaptive ANC, 40 hour battery, multipoint Bluetooth", "18999.00", 25),
                product("DK-USB-C", "USB-C Dock", "Dual 4K output, 2.5 GbE, 100 W pass-through", "11499.00", 30),
                product("CAM-1080", "1080p Webcam", "Auto-focus, dual microphones, privacy shutter", "4299.00", 45),
                product("SSD-2TB", "NVMe SSD 2 TB", "PCIe 4.0, 7000 MB/s reads", "13999.00", 20),
                product("DESK-MAT", "Desk Mat XL", "900 x 400 mm, stitched edges", "1299.00", 100),
                product("LAMP-LED", "Monitor Light Bar", "Asymmetric lighting, auto-dimming", "3499.00", 35),
                product("CHAIR-ERG", "Ergonomic Chair", "Adjustable lumbar support, 4D armrests", "54999.00", 3));
        products.saveAll(demo);
        log.info("Seeded {} demo products", demo.size());
    }

    private static Product product(String sku, String name, String description, String price, int stock) {
        return new Product(sku, name, description, new BigDecimal(price), stock);
    }
}
