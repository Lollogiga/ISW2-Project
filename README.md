# Machine Learning for Software Engineering

---

## 🎯 Goal
Evaluate which **code metrics** have the greatest impact on **method-level buggyness** using Machine Learning models, and analyze the **effects of targeted refactoring** aimed at reducing those metrics.  

---

## 📂 Selected Open-Source Projects
- **Project A:** [BookKeeper](https://github.com/apache/bookkeeper.git)  
- **Project B:** [OpenJPA](https://github.com/apache/openjpa.git)

---

## 🧪 Evaluation Technique
- **Walk-Forward** → used for temporal validation of predictive models across software releases.

---

## 🏷 Labeling Technique
- **Proportion (Incremental method)** → estimates injected versions based on proportions observed in past defects.  
- **Proportion (Cold Start method)** → used when historical data is insufficient to compute incremental proportions.  

---

## 🤖 Classifiers
- **RandomForest**  
- **NaiveBayes**  
- **IBK (k-Nearest Neighbors in Weka)**  

---

## 📊 Metrics Analyzed
- **LOC** → Lines of Code  
- **Cyclomatic Complexity** → Logical complexity of control flow  
- **Churn** → Lines modified in a release  
- **LocAdded** → Lines added in a release  
- **Fan-in / Fan-out** → Coupling between methods  
- **NewcomerRisk** → First-time contributors’ edits  
- **Auth** → Number of different authors  
- **WeekendCommit** → Commits made during weekends  
- **nSmell** → Number of code smells (via PMD)

---

## 🔬 Empirical Validation
1. **Identify** the metrics most correlated with method-level buggyness.  
2. **Train** ML classifiers to predict buggy methods using historical release data.  
3. **Select** the most influential actionable metrics (e.g., Fan-out, code smells).  
4. **Apply refactoring** to reduce these metrics in target methods.  
5. **Evaluate the effect** of metric reduction on maintainability and predicted defect probability.  

---

## 🛠 Refactoring Strategy
- Apply standard refactorings (*Facade, Extract Method, Inline Temp, Remove Duplicates*)  
- Reduce complexity and coupling without altering functionality  
- Focus on metrics with strongest correlation to buggyness (e.g., **Fan-out**, **nSmell**)  

## 🔮 What-If Analysis
The **What-If Analysis** estimates how many buggy methods could have been avoided if specific metrics had been reduced.  

Procedure:
1. Build datasets distinguishing between methods with smells (`B+`) and without smells (`C`).  
2. Create a hypothetical dataset (`B`) where methods from `B+` are re-labeled as if **all code smells were removed**.  
3. Apply the best classifier (identified in experiments) to evaluate differences in predicted buggyness across `B+`, `B`, and `C`.  

Outcomes:
- **Bug Avoidance Rate** → percentage of buggy methods that would no longer be predicted as buggy after smell removal.  
- **Impact on Maintenance** → quantifies the practical benefits of targeted refactoring on software quality.  

---

## 📈 Key Research Questions
- **RQ1**: Which features are most correlated with method-level buggyness?  
- **RQ2**: Which classifier performs best in predicting buggy methods?    
- **RQ3**: What is the impact of hypothetical refactoring (What-If Analysis) on the defect-proneness of methods?  
