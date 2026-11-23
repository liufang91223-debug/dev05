package cn.yunshu.erp;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static cn.yunshu.erp.Orders.require;

@Component
class ProductCategories {
    private final JdbcTemplate db;
    ProductCategories(JdbcTemplate db) { this.db=db; }

    // Taken first inside the register write transaction. Ordinary catalog reads do not take this lock.
    void lock() {
        db.queryForObject("SELECT id FROM product_category_lock WHERE id=1 FOR UPDATE",Integer.class);
    }

    Long resolve(Long id,Object legacyName) {
        if(id!=null || legacyName==null || legacyName.toString().isBlank()) return id;
        var ids=db.queryForList("SELECT id FROM product_category_tree WHERE name=? OR category_path=?",Long.class,legacyName,legacyName);
        require(ids.size()==1,"分类不存在或名称重复，请按完整路径重新选择");
        return ids.getFirst();
    }

    private record Category(Long parent,int level,boolean enabled) {}
    private Category category(long id) {
        return db.query("SELECT parent_id,category_level,enabled FROM product_category_tree WHERE id=?",
            (rs,n)->new Category(rs.getObject(1,Long.class),rs.getInt(2),rs.getBoolean(3)),id)
            .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"所选分类不存在，请刷新"));
    }

    private void changeReference(Long previous,Long next) {
        if(Objects.equals(previous,next)) return;
        if(previous!=null) db.update("UPDATE product_category SET reference_count=reference_count-1 WHERE id=? AND reference_count>0",previous);
        if(next!=null) db.update("UPDATE product_category SET reference_count=reference_count+1 WHERE id=?",next);
    }

    void saveCategory(long id,boolean creating,Long parent,String name) {
        Category old=creating?null:category(id);
        if(creating || !Objects.equals(old.parent(),parent)){
            Category target=parent==null?null:category(parent);
            require(target==null || target.level()<3,"三级分类不能作为上级分类");
            require(target==null || target.enabled(),"请选择已启用的上级分类");
            if(!creating) validateMove(id,parent,target==null?1:target.level()+1);
            if(creating) db.update("INSERT INTO product_category(id,parent_id) VALUES(?,?)",id,parent);
            else db.update("UPDATE product_category SET parent_id=? WHERE id=?",parent,id);
            changeReference(creating?null:old.parent(),parent);
        }
        // Keep the name-based fields readable for integrations built before ID-based categories.
        db.update("UPDATE base_reference b LEFT JOIN base_reference p ON p.id=? SET b.parent=p.name WHERE b.id=?",parent,id);
        db.update("""
            UPDATE base_reference b JOIN product_category c ON c.id=b.id
            SET b.parent=? WHERE c.parent_id=? AND NOT (BINARY b.parent <=> BINARY ?)
            """,name,id,name);
        db.update("UPDATE sku SET category=? WHERE category_id=? AND NOT (BINARY category <=> BINARY ?)",name,id,name);
    }

    private void validateMove(long id,Long parent,int depth) {
        var affected=db.queryForMap("""
            WITH RECURSIVE subtree(id,distance) AS (
                SELECT id,0 FROM product_category WHERE id=?
                UNION ALL SELECT c.id,s.distance+1 FROM product_category c JOIN subtree s ON c.parent_id=s.id
            )
            SELECT MAX(distance) AS height,COUNT(CASE WHEN id=? THEN 1 END) AS cycle,
                COUNT(CASE WHEN distance+?<>3 AND EXISTS(SELECT 1 FROM sku WHERE category_id=subtree.id) THEN 1 END) AS invalid_goods
            FROM subtree
            """,id,parent,depth);
        require(Orders.number(affected,"cycle")==0,"不能选择自身或下级分类作为上级");
        require(depth+Orders.number(affected,"height")<=3,"调整后分类层级将超过三级");
        require(Orders.number(affected,"invalid_goods")==0,"调整后已关联商品的分类必须仍为三级");
    }

    void assignGoods(long sku,Long next) {
        require(next!=null,"请选择启用的末级三级商品分类");
        var target=category(next);
        require(target.enabled() && target.level()==3 && !hasChildren(next),"商品只能选择已启用的末级三级分类");
        Long previous=db.queryForObject("SELECT category_id FROM sku WHERE id=?",Long.class,sku);
        db.update("UPDATE sku s JOIN base_reference b ON b.id=? SET s.category_id=b.id,s.category=b.name WHERE s.id=?",next,sku);
        changeReference(previous,next);
    }

    private boolean hasChildren(long id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM product_category WHERE parent_id=?)",Boolean.class,id));
    }

    void deleteCategory(long id) {
        var node=category(id);
        if(hasChildren(id)) throw conflict("该分类下存在子分类，无法删除");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sku WHERE category_id=?)",Boolean.class,id)))
            throw conflict("该分类已关联商品，无法删除");
        if(db.queryForObject("SELECT reference_count FROM product_category WHERE id=?",Long.class,id)>0)
            throw conflict("该分类的引用次数大于零，无法删除");
        db.update("DELETE FROM product_category WHERE id=?",id);
        db.update("DELETE FROM base_reference WHERE id=? AND family='category'",id);
        changeReference(node.parent(),null);
    }

    void deleteGoods(long id) {
        var rows=db.queryForList("SELECT category_id FROM sku WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"商品不存在");
        // Draft warehouse lines can reference a product before any stock or order row exists.
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM business_document_line WHERE sku_id=?)",Boolean.class,id)))
            throw conflict("商品已被业务单据使用，无法删除");
        db.update("DELETE FROM sku WHERE id=?",id);
        changeReference((Long)rows.getFirst().get("category_id"),null);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
}
