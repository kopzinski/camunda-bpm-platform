/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl;

import static org.camunda.bpm.engine.impl.util.EnsureUtil.ensureNotNull;
import static org.camunda.bpm.engine.impl.util.EnsureUtil.ensureNull;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.exception.NotValidException;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.db.ListQueryParameterObject;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.interceptor.CommandExecutor;
import org.camunda.bpm.engine.query.Query;
import org.camunda.bpm.engine.query.QueryProperty;
import org.joda.time.DateTime;


/**
 * Abstract superclass for all query types.
 *
 * @author Joram Barrez
 */
public abstract class AbstractQuery<T extends Query<?,?>, U> extends ListQueryParameterObject implements Command<Object>, Query<T,U>, Serializable {

  private static final long serialVersionUID = 1L;

  public static final String SORTORDER_ASC = "asc";
  public static final String SORTORDER_DESC = "desc";

  private static enum ResultType {
    LIST, LIST_PAGE, SINGLE_RESULT, COUNT
  }
  protected transient CommandExecutor commandExecutor;
  protected transient CommandContext commandContext;

  protected ResultType resultType;

  protected List<QueryOrderingProperty> orderingProperties = new ArrayList<QueryOrderingProperty>();

  protected Map<String, String> expressions = new HashMap<String, String>();

  protected AbstractQuery() {
  }

  protected AbstractQuery(CommandExecutor commandExecutor) {
    this.commandExecutor = commandExecutor;
  }

  public AbstractQuery(CommandContext commandContext) {
    this.commandContext = commandContext;
  }

  public AbstractQuery<T, U> setCommandExecutor(CommandExecutor commandExecutor) {
    this.commandExecutor = commandExecutor;
    return this;
  }

  public T orderBy(QueryProperty property) {
    return orderBy(new QueryOrderingProperty(null, property));
  }

  @SuppressWarnings("unchecked")
  public T orderBy(QueryOrderingProperty orderProperty) {
    this.orderingProperties.add(orderProperty);
    return (T) this;
  }

  public T asc() {
    return direction(Direction.ASCENDING);
  }

  public T desc() {
    return direction(Direction.DESCENDING);
  }

  @SuppressWarnings("unchecked")
  public T direction(Direction direction) {
    QueryOrderingProperty currentOrderingProperty = null;

    if (!orderingProperties.isEmpty()) {
      currentOrderingProperty = orderingProperties.get(orderingProperties.size() - 1);
    }

    ensureNotNull(NotValidException.class, "You should call any of the orderBy methods first before specifying a direction", "currentOrderingProperty", currentOrderingProperty);

    if (currentOrderingProperty.getDirection() != null) {
      ensureNull(NotValidException.class, "Invalid query: can specify only one direction desc() or asc() for an ordering constraint", "direction", direction);
    }

    currentOrderingProperty.setDirection(direction);
    return (T) this;
  }

  protected void checkQueryOk() {
//    if (orderProperty != null) {
//      throw new NotValidException("Invalid query: call asc() or desc() after using orderByXX()");
//    }

    for (QueryOrderingProperty orderingProperty : orderingProperties) {
      ensureNotNull(NotValidException.class, "Invalid query: call asc() or desc() after using orderByXX()", "direction", orderingProperty.getDirection());
    }
  }

  @SuppressWarnings("unchecked")
  public U singleResult() {
    this.resultType = ResultType.SINGLE_RESULT;
    if (commandExecutor!=null) {
      return (U) commandExecutor.execute(this);
    }
    return executeSingleResult(Context.getCommandContext());
  }

  @SuppressWarnings("unchecked")
  public List<U> list() {
    this.resultType = ResultType.LIST;
    if (commandExecutor!=null) {
      return (List<U>) commandExecutor.execute(this);
    }
    return evaluateExpressionsAndExecuteList(Context.getCommandContext(), null);
  }

  @SuppressWarnings("unchecked")
  public List<U> listPage(int firstResult, int maxResults) {
    this.firstResult = firstResult;
    this.maxResults = maxResults;
    this.resultType = ResultType.LIST_PAGE;
    if (commandExecutor!=null) {
      return (List<U>) commandExecutor.execute(this);
    }
    return evaluateExpressionsAndExecuteList(Context.getCommandContext(), new Page(firstResult, maxResults));
  }

  public long count() {
    this.resultType = ResultType.COUNT;
    if (commandExecutor!=null) {
      return (Long) commandExecutor.execute(this);
    }
    return evaluateExpressionsAndExecuteCount(Context.getCommandContext());
  }

  public Object execute(CommandContext commandContext) {
    if (resultType==ResultType.LIST) {
      return evaluateExpressionsAndExecuteList(commandContext, null);
    } else if (resultType==ResultType.SINGLE_RESULT) {
      return executeSingleResult(commandContext);
    } else if (resultType==ResultType.LIST_PAGE) {
      return evaluateExpressionsAndExecuteList(commandContext, null);
    } else {
      return evaluateExpressionsAndExecuteCount(commandContext);
    }
  }

  public long evaluateExpressionsAndExecuteCount(CommandContext commandContext) {
    evaluateExpressions();
    return executeCount(commandContext);
  }

  public abstract long executeCount(CommandContext commandContext);

  public List<U> evaluateExpressionsAndExecuteList(CommandContext commandContext, Page page) {
    evaluateExpressions();
    return executeList(commandContext, page);
  }

  /**
   * Executes the actual query to retrieve the list of results.
   * @param page used if the results must be paged. If null, no paging will be applied.
   */
  public abstract List<U> executeList(CommandContext commandContext, Page page);

  public U executeSingleResult(CommandContext commandContext) {
    List<U> results = evaluateExpressionsAndExecuteList(commandContext, null);
    if (results.size() == 1) {
      return results.get(0);
    } else if (results.size() > 1) {
     throw new ProcessEngineException("Query return "+results.size()+" results instead of max 1");
    }
    return null;
  }

  protected void addOrder(String column, String sortOrder) {
    if (orderBy==null) {
      orderBy = "";
    } else {
      orderBy = orderBy+", ";
    }
    orderBy = orderBy+column+" "+sortOrder;
  }

  @Deprecated
  public String getOrderBy() {
    if(orderBy == null) {
      return super.getOrderBy();
    } else {
      return orderBy;
    }
  }

  public List<QueryOrderingProperty> getOrderingProperties() {
    return orderingProperties;
  }

  public void setOrderingProperties(List<QueryOrderingProperty> orderingProperties) {
    this.orderingProperties = orderingProperties;
  }

  public Map<String, String> getExpressions() {
    return expressions;
  }

  public void setExpressions(Map<String, String> expressions) {
    this.expressions = expressions;
  }

  public void addExpression(String key, String expression) {
    this.expressions.put(key, expression);
  }

  protected void evaluateExpressions() {
    // we cannot iterate directly on the entry set cause the expressions
    // are removed by the setter methods during the iteration
    ArrayList<Map.Entry<String, String>> entries = new ArrayList<Map.Entry<String, String>>(expressions.entrySet());

    for (Map.Entry<String, String> entry : entries) {
      String methodName = entry.getKey();
      String expression = entry.getValue();

      Object value;

      try {
        value = Context.getProcessEngineConfiguration()
          .getExpressionManager()
          .createExpression(expression)
          .getValue(null);
      }
      catch (ProcessEngineException e) {
        throw new ProcessEngineException("Unable to resolve expression '" + expression + "' for method '" + methodName + "' on class '" + getClass().getCanonicalName() + "'", e);
      }

      // automatically convert DateTime to date
      if (value instanceof DateTime) {
        value = ((DateTime) value).toDate();
      }

      try {
        Method method = getMethod(methodName);
        method.invoke(this, value);
      } catch (InvocationTargetException e) {
        throw new ProcessEngineException("Unable to invoke method '" + methodName + "' on class '" + getClass().getCanonicalName() + "'", e);
      } catch (IllegalAccessException e) {
        throw new ProcessEngineException("Unable to access method '" + methodName + "' on class '" + getClass().getCanonicalName() + "'", e);
      }
    }
  }

  protected Method getMethod(String methodName) {
    for (Method method : getClass().getDeclaredMethods()) {
      if (method.getName().equals(methodName)) {
        return method;
      }
    }
    throw new ProcessEngineException("Unable to find method '" + methodName + "' on class '" + getClass().getCanonicalName() + "'");
  }

  public T extend(T extendingQuery) {
    throw new ProcessEngineException("Extending of query type '" + extendingQuery.getClass().getCanonicalName() + "' currently not supported");
  }

  protected void mergeOrdering(AbstractQuery<?, ?> extendedQuery, AbstractQuery<?, ?> extendingQuery) {
    extendedQuery.orderingProperties = this.orderingProperties;
    if (extendingQuery.orderingProperties != null) {
       if (extendedQuery.orderingProperties == null) {
         extendedQuery.orderingProperties = extendingQuery.orderingProperties;
       }
       else {
         extendedQuery.orderingProperties.addAll(extendingQuery.orderingProperties);
       }
    }
  }

  protected void mergeExpressions(AbstractQuery<?, ?> extendedQuery, AbstractQuery<?, ?> extendingQuery) {
    Map<String, String> mergedExpressions = new HashMap<String, String>(extendingQuery.getExpressions());
    for (Map.Entry<String, String> entry : this.getExpressions().entrySet()) {
      if (!mergedExpressions.containsKey(entry.getKey())) {
        mergedExpressions.put(entry.getKey(), entry.getValue());
      }
    }
    extendedQuery.setExpressions(mergedExpressions);
  }

}
