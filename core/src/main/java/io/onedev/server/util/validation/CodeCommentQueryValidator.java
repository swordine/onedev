package io.onedev.server.util.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import io.onedev.server.model.Project;
import io.onedev.server.util.OneContext;
import io.onedev.server.web.editable.annotation.CodeCommentQuery;
import io.onedev.utils.StringUtils;

public class CodeCommentQueryValidator implements ConstraintValidator<CodeCommentQuery, String> {

	@Override
	public void initialize(CodeCommentQuery constaintAnnotation) {
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext constraintContext) {
		if (value == null) {
			return true;
		} else {
			Project project = OneContext.get().getProject();
			try {
				io.onedev.server.entityquery.codecomment.CodeCommentQuery.parse(project, value, true);
				return true;
			} catch (Exception e) {
				constraintContext.disableDefaultConstraintViolation();
				String message;
				if (StringUtils.isNotBlank(e.getMessage()))
					message = e.getMessage();
				else
					message = "Malformed code comment query";
				
				constraintContext.buildConstraintViolationWithTemplate(message).addConstraintViolation();
				return false;
			}
		}
	}
	
}
