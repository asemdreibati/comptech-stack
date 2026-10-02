package io.souqly.catalog.category;

import java.time.Clock;
import java.util.List;

import io.souqly.catalog.formio.FormDefinition;
import io.souqly.catalog.formio.FormioClient;
import io.souqly.catalog.i18n.LocalizedText;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Service
public class CategoryService {

    private final MongoTemplate mongo;
    private final FormioClient formio;
    private final Clock clock;

    public CategoryService(MongoTemplate mongo, FormioClient formio, Clock clock) {
        this.mongo = mongo;
        this.formio = formio;
        this.clock = clock;
    }

    /** Creates or updates a category. The form must already exist in Form.io. */
    public Category save(String slug, LocalizedText name, String formPath) {
        formio.form(formPath);
        var now = clock.instant();
        var update = new Update().set("name", name).set("formPath", formPath).set("updatedAt", now)
                .setOnInsert("createdAt", now);
        return mongo.findAndModify(query(where("_id").is(slug)), update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), Category.class);
    }

    public Category get(String slug) {
        var category = mongo.findById(slug, Category.class);
        if (category == null) {
            throw new CategoryNotFoundException(slug);
        }
        return category;
    }

    public List<Category> list() {
        return mongo.find(new Query().with(Sort.by("_id")), Category.class);
    }

    public FormDefinition form(String slug) {
        return formio.form(get(slug).formPath());
    }
}
