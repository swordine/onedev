package io.onedev.server.manager.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.lang3.time.DateUtils;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import io.onedev.launcher.loader.Listen;
import io.onedev.launcher.loader.ListenerRegistry;
import io.onedev.server.entityquery.EntityQuery;
import io.onedev.server.entityquery.EntitySort;
import io.onedev.server.entityquery.EntitySort.Direction;
import io.onedev.server.entityquery.QueryBuildContext;
import io.onedev.server.entityquery.codecomment.CodeCommentQuery;
import io.onedev.server.entityquery.codecomment.CodeCommentQueryBuildContext;
import io.onedev.server.event.codecomment.CodeCommentAdded;
import io.onedev.server.event.codecomment.CodeCommentEvent;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.command.RevListCommand;
import io.onedev.server.manager.CodeCommentManager;
import io.onedev.server.manager.CommitInfoManager;
import io.onedev.server.model.CodeComment;
import io.onedev.server.model.CodeCommentRelation;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.support.TextRange;
import io.onedev.server.model.support.codecomment.CodeCommentConstants;
import io.onedev.server.persistence.annotation.Sessional;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.persistence.dao.AbstractEntityManager;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.persistence.dao.EntityCriteria;
import io.onedev.server.util.diff.DiffUtils;
import io.onedev.server.util.diff.WhitespaceOption;

@Singleton
public class DefaultCodeCommentManager extends AbstractEntityManager<CodeComment> implements CodeCommentManager {

	private final int MAX_HISTORY_COMMITS_TO_CHECK = 50000;
	
	private final int MAX_HISTORY_FILES_TO_CHECK = 500;
	
	private final ListenerRegistry listenerRegistry;
	
	private final CommitInfoManager commitInfoManager;
	
	@Inject
	public DefaultCodeCommentManager(Dao dao, ListenerRegistry listenerRegistry, CommitInfoManager commitInfoManager) {
		super(dao);
		this.listenerRegistry = listenerRegistry;
		this.commitInfoManager = commitInfoManager;
	}

	@Transactional
	@Override
	public void save(CodeComment comment, PullRequest request) {
		if (comment.isNew()) {
			dao.persist(comment);
			CodeCommentAdded event = new CodeCommentAdded(comment, request);
			listenerRegistry.post(event);
		} else {
			dao.persist(comment);
		}
	}

	@Transactional
	@Override
	public void delete(CodeComment codeComment) {
		super.delete(codeComment);
		for (CodeCommentRelation relation: codeComment.getRelations()) {
			PullRequest request = relation.getRequest();
			request.setCommentCount(request.getCommentCount()-codeComment.getReplyCount()-1);
		}
	}

	@Sessional
	@Override
	public CodeComment find(String uuid) {
		EntityCriteria<CodeComment> criteria = newCriteria();
		criteria.add(Restrictions.eq("uuid", uuid));
		return find(criteria);
	}
	
	@Transactional
	@Listen
	public void on(CodeCommentEvent event) {
		event.getComment().setLastActivity(event);
	}
	
	@Sessional
	@Override
	public List<CodeComment> findAllAfter(Project project, String commentUUID, int count) {
		EntityCriteria<CodeComment> criteria = newCriteria();
		criteria.add(Restrictions.eq("project", project));
		criteria.addOrder(Order.asc("id"));
		if (commentUUID != null) {
			CodeComment comment = find(commentUUID);
			if (comment != null) {
				criteria.add(Restrictions.gt("id", comment.getId()));
			}
		}
		return findRange(criteria, 0, count);
	}

	@Sessional
	@Override
	public Collection<CodeComment> findAll(Project project, ObjectId commitId, String path) {
		EntityCriteria<CodeComment> criteria = newCriteria();
		criteria.add(Restrictions.eq("project", project));
		criteria.add(Restrictions.eq("markPos.commit", commitId.name()));
		if (path != null)
			criteria.add(Restrictions.eq("markPos.path", path));
		return findAll(criteria);
	}

	@Sessional
	@Override
	public Collection<CodeComment> findAll(Project project, ObjectId... commitIds) {
		Preconditions.checkArgument(commitIds.length > 0);
		
		EntityCriteria<CodeComment> criteria = newCriteria();
		criteria.add(Restrictions.eq("project", project));
		List<Criterion> criterions = new ArrayList<>();
		for (ObjectId commitId: commitIds) {
			criterions.add(Restrictions.eq("markPos.commit", commitId.name()));
		}
		criteria.add(Restrictions.or(criterions.toArray(new Criterion[criterions.size()])));
		return findAll(criteria);
	}
	
	@Override
	public Map<CodeComment, TextRange> findHistory(Project project, ObjectId commitId, String path) {
		Map<CodeComment, TextRange> comments = new HashMap<>();
		
		Map<String, Map<String, List<CodeComment>>> possibleComments = new HashMap<>();
		for (String possibleHistoryPath: commitInfoManager.getHistoryPaths(project, path)) {
			EntityCriteria<CodeComment> criteria = EntityCriteria.of(CodeComment.class);
			criteria.add(Restrictions.eq("markPos.path", possibleHistoryPath));
			for (CodeComment comment: findAll(criteria)) {
				if (comment.getMarkPos().getCommit().equals(commitId.name()) && possibleHistoryPath.equals(path)) {
					comments.put(comment, comment.getMarkPos().getRange());
				} else {
					Map<String, List<CodeComment>> commentsOnCommit = 
							possibleComments.get(comment.getMarkPos().getCommit());
					if (commentsOnCommit == null) {
						commentsOnCommit = new HashMap<>();
						possibleComments.put(comment.getMarkPos().getCommit(), commentsOnCommit);
					}
					List<CodeComment> commentsOnPath = commentsOnCommit.get(possibleHistoryPath);
					if (commentsOnPath == null) {
						commentsOnPath = new ArrayList<>();
						commentsOnCommit.put(possibleHistoryPath, commentsOnPath);
					}
					commentsOnPath.add(comment);
				}
			}
		}

		try (RevWalk revWalk = new RevWalk(project.getRepository())) {
			Date oldestDate = null;
			List<RevCommit> historyCommits = new ArrayList<>();
			for (Map.Entry<String, Map<String, List<CodeComment>>> entry: possibleComments.entrySet()) {
				try {
					RevCommit commit = revWalk.parseCommit(ObjectId.fromString(entry.getKey()));
					historyCommits.add(commit);
					PersonIdent committer = commit.getCommitterIdent();
					if (committer != null && committer.getWhen() != null 
							&& (oldestDate == null || committer.getWhen().before(oldestDate))) {
						oldestDate = committer.getWhen();
					}
				} catch (MissingObjectException e) {
				}
			}
			
			if (oldestDate != null) {
				RevListCommand command = new RevListCommand(project.getRepository().getDirectory());
				command.after(DateUtils.addDays(oldestDate, -1));
				command.revisions(Lists.newArrayList(commitId.name()));
				command.count(MAX_HISTORY_COMMITS_TO_CHECK);
				Set<String> revisions = new HashSet<>(command.call());
				
				RevCommit commit = revWalk.parseCommit(commitId);
				List<String> newLines = GitUtils.readLines(project.getRepository(), commit, path, 
						WhitespaceOption.DEFAULT);

				Collections.sort(historyCommits, new Comparator<RevCommit>() {

					@Override
					public int compare(RevCommit o1, RevCommit o2) {
						return o2.getCommitTime() - o1.getCommitTime();
					}
					
				});
				int checkedHistoryFiles = 0;
				for (RevCommit historyCommit: historyCommits) {
					if (revisions.contains(historyCommit.name())) {
						Map<String, List<CodeComment>> commentsOnCommit = 
								Preconditions.checkNotNull(possibleComments.get(historyCommit.name()));
						for (Map.Entry<String, List<CodeComment>> pathEntry: commentsOnCommit.entrySet()) {
							List<String> oldLines = GitUtils.readLines(project.getRepository(), historyCommit, 
									pathEntry.getKey(), WhitespaceOption.DEFAULT);
							Map<Integer, Integer> lineMapping = DiffUtils.mapLines(oldLines, newLines);
							for (CodeComment comment: pathEntry.getValue()) {
								TextRange newRange = DiffUtils.mapRange(lineMapping, comment.getMarkPos().getRange());
								if (newRange != null) 
									comments.put(comment, newRange);
							}
							if (++checkedHistoryFiles == MAX_HISTORY_FILES_TO_CHECK) {
								return comments;
							}
						}
					}
				}
			} 
			
			return comments;
		} catch (IOException e) {
			throw new RuntimeException(e);
		} 

	}

	private Predicate[] getPredicates(io.onedev.server.entityquery.EntityCriteria<CodeComment> criteria, Project project, 
			PullRequest request, QueryBuildContext<CodeComment> context) {
		List<Predicate> predicates = new ArrayList<>();
		if (request != null) {
			Join<?, ?> relations = context.getRoot().join(CodeCommentConstants.ATTR_RELATIONS, JoinType.INNER);
			relations.on(context.getBuilder().equal(relations.get(CodeCommentRelation.ATTR_REQUEST), request));
		} else {
			predicates.add(context.getBuilder().equal(context.getRoot().get("project"), project));
		}
		if (criteria != null)
			predicates.add(criteria.getPredicate(project, context));
		return predicates.toArray(new Predicate[0]);
	}
	
	private CriteriaQuery<CodeComment> buildCriteriaQuery(Session session, Project project, PullRequest request, 
			EntityQuery<CodeComment> requestQuery) {
		CriteriaBuilder builder = session.getCriteriaBuilder();
		CriteriaQuery<CodeComment> query = builder.createQuery(CodeComment.class);
		Root<CodeComment> root = query.from(CodeComment.class);
		query.select(root).distinct(true);
		
		QueryBuildContext<CodeComment> context = new CodeCommentQueryBuildContext(root, builder);
		query.where(getPredicates(requestQuery.getCriteria(), project, request, context));

		List<javax.persistence.criteria.Order> orders = new ArrayList<>();
		for (EntitySort sort: requestQuery.getSorts()) {
			if (sort.getDirection() == Direction.ASCENDING)
				orders.add(builder.asc(CodeCommentQuery.getPath(root, CodeCommentConstants.ORDER_FIELDS.get(sort.getField()))));
			else
				orders.add(builder.desc(CodeCommentQuery.getPath(root, CodeCommentConstants.ORDER_FIELDS.get(sort.getField()))));
		}

		Path<String> idPath = root.get("id");
		if (orders.isEmpty())
			orders.add(builder.desc(idPath));
		query.orderBy(orders);
		
		return query;
	}

	@Sessional
	@Override
	public List<CodeComment> query(Project project, PullRequest request, EntityQuery<CodeComment> commentQuery, 
			int firstResult, int maxResults) {
		CriteriaQuery<CodeComment> criteriaQuery = buildCriteriaQuery(getSession(), project, request, commentQuery);
		Query<CodeComment> query = getSession().createQuery(criteriaQuery);
		query.setFirstResult(firstResult);
		query.setMaxResults(maxResults);
		return query.getResultList();
	}
	
	@Sessional
	@Override
	public int count(Project project, PullRequest request, 
			io.onedev.server.entityquery.EntityCriteria<CodeComment> commentCriteria) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Long> criteriaQuery = builder.createQuery(Long.class);
		Root<CodeComment> root = criteriaQuery.from(CodeComment.class);

		QueryBuildContext<CodeComment> context = new CodeCommentQueryBuildContext(root, builder);
		criteriaQuery.where(getPredicates(commentCriteria, project, request, context));

		criteriaQuery.select(builder.countDistinct(root));
		return getSession().createQuery(criteriaQuery).uniqueResult().intValue();
	}
	
}
